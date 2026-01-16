package com.maxrave.media3.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Orchestrates song transitions by observing the player state and
 * commanding the DualPlayerEngine.
 *
 * This controller reads crossfade settings from the DataStoreManager and
 * schedules transitions accordingly.
 */
@UnstableApi
class TransitionController(
    private val engine: DualPlayerEngine,
    private val dataStoreManager: DataStoreManager,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var transitionListener: Player.Listener? = null
    private var transitionSchedulerJob: Job? = null
    private var currentObservedPlayer: Player? = null

    private val swapListener: (Player) -> Unit = { newPlayer ->
        Logger.d(TAG, "Controller detected player swap. Moving listener.")
        transitionListener?.let { listener ->
            currentObservedPlayer?.removeListener(listener)
            currentObservedPlayer = newPlayer
            newPlayer.addListener(listener)

            // Trigger check for the new player immediately
            if (newPlayer.isPlaying) {
                newPlayer.currentMediaItem?.let { scheduleTransitionFor(it) }
            }
        }
    }

    /**
     * Attaches the controller to the player engine to start listening for state changes.
     */
    fun initialize() {
        if (transitionListener != null) return // Already initialized

        Logger.d(TAG, "Initializing TransitionController...")

        transitionListener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Logger.d(TAG, "onMediaItemTransition: ${mediaItem?.mediaId} (reason=$reason)")
                // When we naturally move to a new song, ensure pauseAtEnd is OFF by default.
                engine.setPauseAtEndOfMediaItems(false)

                if (mediaItem != null) {
                    scheduleTransitionFor(mediaItem)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val job = transitionSchedulerJob
                if (isPlaying && (job == null || job.isCompleted)) {
                    // If playback resumes and no transition is scheduled, schedule one.
                    Logger.d(TAG, "Playback resumed. Checking if transition needs scheduling.")
                    engine.masterPlayer.currentMediaItem?.let { scheduleTransitionFor(it) }
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    // The queue has changed (e.g., reordered, item removed).
                    Logger.d(TAG, "Timeline changed (reason=$reason). Cancelling pending transition.")
                    transitionSchedulerJob?.cancel()
                    engine.cancelNext()

                    // Try to reschedule for the current item
                    engine.masterPlayer.currentMediaItem?.let { scheduleTransitionFor(it) }
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                Logger.d(TAG, "Repeat mode changed to $repeatMode. Rescheduling transition.")
                transitionSchedulerJob?.cancel()
                engine.cancelNext()
                engine.masterPlayer.currentMediaItem?.let { scheduleTransitionFor(it) }
            }
        }

        // Initial setup
        currentObservedPlayer = engine.masterPlayer
        currentObservedPlayer?.addListener(transitionListener!!)
        engine.addPlayerSwapListener(swapListener)
    }

    private fun scheduleTransitionFor(currentMediaItem: MediaItem) {
        // Cancel any existing job first
        transitionSchedulerJob?.cancel()

        transitionSchedulerJob = scope.launch {
            // WAIT for any active transition to finish.
            while (engine.isTransitionRunning()) {
                Logger.d(TAG, "Waiting for active transition to finish before scheduling next...")
                delay(500)
            }

            // Check if crossfade is enabled (returns "TRUE"/"FALSE" string)
            val isCrossfadeEnabled = dataStoreManager.crossfadeEnabled.first() == DataStoreManager.Values.TRUE
            if (!isCrossfadeEnabled) {
                Logger.d(TAG, "Crossfade disabled. Using default gap.")
                engine.setPauseAtEndOfMediaItems(false)
                return@launch
            }

            val crossfadeDuration = dataStoreManager.crossfadeDuration.first()
            if (crossfadeDuration <= 0) {
                Logger.d(TAG, "Crossfade duration is 0. Using default gap.")
                engine.setPauseAtEndOfMediaItems(false)
                return@launch
            }

            val player = engine.masterPlayer
            val repeatMode = player.repeatMode
            val nextIndex = player.currentMediaItemIndex + 1

            val nextMediaItem = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> currentMediaItem // Loop the same track
                else -> if (nextIndex < player.mediaItemCount) player.getMediaItemAt(nextIndex) else null
            }

            // If there is no next track and we're not looping, cancel any pending transition and stop.
            if (nextMediaItem == null) {
                Logger.d(TAG, "No next track (index=$nextIndex, count=${player.mediaItemCount}, repeatMode=$repeatMode). No transition.")
                engine.cancelNext()
                return@launch
            }

            val targetIndex = if (repeatMode == Player.REPEAT_MODE_ONE) player.currentMediaItemIndex else nextIndex
            Logger.d(TAG, "Preparing next track: ${nextMediaItem.mediaId} (Index: $targetIndex)")
            engine.prepareNext(nextMediaItem)

            // Wait for the player to report a valid duration.
            var duration = player.duration
            while ((duration == C.TIME_UNSET || duration <= 0) && isActive) {
                delay(500)
                duration = player.duration
            }

            if (!isActive) return@launch

            val minFade = 500L
            val guardWindow = 150L

            if (duration < minFade + guardWindow) {
                Logger.w(TAG, "Track too short for crossfade (duration=$duration).")
                engine.setPauseAtEndOfMediaItems(false)
                return@launch
            }

            val maxFadeDuration = (duration - guardWindow).coerceAtLeast(minFade)
            val effectiveDuration = (crossfadeDuration * 1000L) // Convert seconds to ms
                .coerceAtLeast(minFade)
                .coerceAtMost(maxFadeDuration)

            val transitionPoint = duration - effectiveDuration

            Logger.d(TAG, "Scheduled crossfade at $transitionPoint ms (SongDur: $duration). Fade duration: $effectiveDuration ms")

            // Enable Pause At End to control the transition manually
            engine.setPauseAtEndOfMediaItems(true)

            if (transitionPoint <= player.currentPosition) {
                val remaining = duration - player.currentPosition
                val adjustedDuration = (remaining - guardWindow).coerceAtLeast(minFade)
                if (remaining > guardWindow + minFade / 2) {
                    Logger.w(TAG, "Already past transition point! Triggering immediately.")
                    engine.performTransition(TransitionSettings(durationMs = adjustedDuration.toInt()))
                } else {
                    Logger.w(TAG, "Too close to end ($remaining ms left). Skipping to avoid glitch.")
                    engine.setPauseAtEndOfMediaItems(false)
                }
                return@launch
            }

            // Wait loop with adaptive sleep
            while (player.currentPosition < transitionPoint && isActive) {
                val remaining = transitionPoint - player.currentPosition
                val sleep = when {
                    remaining > 5000 -> 1000L
                    remaining > 1000 -> 250L
                    else -> 50L // Tight loop near the end
                }
                delay(sleep)
            }

            // Final check to ensure the job wasn't cancelled while waiting.
            if (isActive) {
                Logger.d(TAG, "FIRING TRANSITION NOW!")
                engine.performTransition(TransitionSettings(durationMs = effectiveDuration.toInt()))
            } else {
                Logger.d(TAG, "Job cancelled before firing.")
                engine.setPauseAtEndOfMediaItems(false)
            }
        }
    }

    /**
     * Cleans up resources and listeners.
     */
    fun release() {
        Logger.d(TAG, "Releasing controller.")
        transitionSchedulerJob?.cancel()
        engine.removePlayerSwapListener(swapListener)
        transitionListener?.let { currentObservedPlayer?.removeListener(it) }
        transitionListener = null
        currentObservedPlayer = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "TransitionController"
    }
}
