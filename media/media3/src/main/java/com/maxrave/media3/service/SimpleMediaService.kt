package com.maxrave.media3.service

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.maxrave.common.Config
import com.maxrave.common.MEDIA_NOTIFICATION
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.extension.toCommandButton
import com.maxrave.media3.utils.CoilBitmapLoader
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import kotlin.system.exitProcess

@UnstableApi
internal class SimpleMediaService :
    MediaLibraryService(),
    KoinComponent {
    private val player: ExoPlayer by inject<ExoPlayer>(named(Config.MAIN_PLAYER))
    private val coilBitmapLoader: CoilBitmapLoader by inject<CoilBitmapLoader>()

    private var mediaSession: MediaLibrarySession? = null

    private val simpleMediaSessionCallback: MediaLibrarySession.Callback by inject<MediaLibrarySession.Callback>()

    private val simpleMediaServiceHandler: MediaPlayerHandler by inject<MediaPlayerHandler>()

    private val binder = MusicBinder()

    private var invincibility: Invincibility? = null

    private val handler = Handler(Looper.getMainLooper())

    private inner class Invincibility :
        BroadcastReceiver(),
        Runnable {
        private var isStarted = false
        private val intervalMs = 30_000L

        override fun onReceive(
            context: Context?,
            intent: Intent?,
        ) {
            Logger.d("Invincibility", "Received intent: ${intent?.action}")
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> handler.post(this)
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(this)
                    Logger.d("Invincibility", "Screen off detected, updating notification")
                    val controllerState = simpleMediaServiceHandler.controlState.value
                    simpleMediaServiceHandler.onUpdateNotification(
                        listOf(
                            GenericCommandButton.Like(controllerState.isLiked),
                            GenericCommandButton.Repeat(repeatState = controllerState.repeatState),
                            GenericCommandButton.Radio,
                            GenericCommandButton.Shuffle(isShuffled = controllerState.isShuffle),
                        ),
                    )
                }
            }
        }

        @Synchronized
        fun start() {
            if (!isStarted) {
                Logger.d("Invincibility", "Starting invincibility")
                isStarted = true
                handler.postDelayed(this, intervalMs)
                registerReceiver(
                    this,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                    },
                )
            }
        }

        @Synchronized
        fun stop() {
            if (isStarted) {
                Logger.d("Invincibility", "Stopping invincibility")
                handler.removeCallbacks(this)
                unregisterReceiver(this)
                isStarted = false
            }
        }

        override fun run() {
            Logger.d("Invincibility", "Invincibility heartbeat - updating notification")
            val controllerState = simpleMediaServiceHandler.controlState.value
            simpleMediaServiceHandler.onUpdateNotification(
                listOf(
                    GenericCommandButton.Like(controllerState.isLiked),
                    GenericCommandButton.Repeat(repeatState = controllerState.repeatState),
                    GenericCommandButton.Radio,
                    GenericCommandButton.Shuffle(isShuffled = controllerState.isShuffle),
                ),
            )
            handler.postDelayed(this, intervalMs)
        }
    }

    inner class MusicBinder : Binder() {
        val service: SimpleMediaService
            get() = this@SimpleMediaService

        fun setActivitySession(
            context: Context,
            activity: Class<out Activity>,
        ) {
            mediaSession?.setSessionActivity(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, activity),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Logger.w("Service", "Simple Media Service Bound")
        return super.onBind(intent) ?: binder
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        Logger.w("Service", "Simple Media Service Created")
        invincibility = Invincibility()
        invincibility?.start()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { MEDIA_NOTIFICATION.NOTIFICATION_ID },
                MEDIA_NOTIFICATION.NOTIFICATION_CHANNEL_ID,
                R.string.notification_channel_name,
            ).apply {
                setSmallIcon(R.drawable.mono)
            },
        )

        mediaSession =
            provideMediaLibrarySession(
                this,
                player,
                simpleMediaSessionCallback,
            )

        simpleMediaServiceHandler.onUpdateNotification = { list ->
            mediaSession?.setCustomLayout(
                list.map {
                    it.toCommandButton(this)
                },
            )
        }

        val sessionToken = SessionToken(this, ComponentName(this, SimpleMediaService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())
    }

    @UnstableApi
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Logger.w("Service", "Simple Media Service Received Action: ${intent?.action}")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    @UnstableApi
    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    @UnstableApi
    fun release() {
        Logger.w("Service", "Starting release process")
        invincibility?.stop()
        invincibility = null
        runBlocking {
            try {
                // Release MediaSession and Player
                mediaSession?.run {
                    this.player.pause()
                    this.player.playWhenReady = false
                    this.player.release()
                    this.release()
                }
                // Release handler first (contains coroutines and jobs)
                simpleMediaServiceHandler.release()
                mediaSession = null
                Logger.w("Service", "Simple Media Service Released")
            } catch (e: Exception) {
                Logger.e("Service", "Error during release")
            }
        }
    }

    @UnstableApi
    override fun onDestroy() {
        super.onDestroy()
        Logger.w("Service", "Simple Media Service Destroyed")
    }

    override fun onTrimMemory(level: Int) {
        Logger.w("Service", "Simple Media Service Trim Memory Level: $level")
        simpleMediaServiceHandler.mayBeSaveRecentSong()
    }

    @UnstableApi
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.w("Service", "Simple Media Service Task Removed")
        if (simpleMediaServiceHandler.shouldReleaseOnTaskRemoved()) {
            release()
            super.onTaskRemoved(rootIntent)
            exitProcess(0)
        }
    }

    // Can't inject by Koin because it depend on service
    @UnstableApi
    private fun provideMediaLibrarySession(
        service: MediaLibraryService,
        player: ExoPlayer,
        callback: MediaLibrarySession.Callback,
    ): MediaLibrarySession =
        MediaLibrarySession
            .Builder(
                service,
                player,
                callback,
            ).setId(this.javaClass.name)
            .setBitmapLoader(coilBitmapLoader)
            .build()
}