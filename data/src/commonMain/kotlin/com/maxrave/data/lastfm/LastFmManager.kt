package com.maxrave.data.lastfm

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages Last.fm scrobbling state and orchestrates API calls.
 *
 * Handles:
 * - Track start events (Now Playing)
 * - Track completion events (Scrobble)
 * - Scrobble eligibility (30 seconds or 50% of track duration)
 * - Pending scrobbles cache for offline/failed scrobbles
 */
class LastFmManager(
    private val lastFmApi: LastFmApi,
    private val dataStoreManager: DataStoreManager,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Current track info for scrobbling
    private var currentTrack: TrackInfo? = null
    private var playbackStartTime: Long = 0
    private var totalPlayedMs: Long = 0
    private var isPaused: Boolean = false

    data class TrackInfo(
        val artist: String,
        val track: String,
        val album: String?,
        val durationMs: Long,
    )

    /**
     * Called when a new track starts playing.
     * Updates Now Playing status on Last.fm.
     */
    fun onTrackStart(
        artist: String,
        track: String,
        album: String?,
        durationMs: Long,
    ) {
        // First, check if the previous track should be scrobbled
        checkAndScrobbleCurrentTrack()

        // Reset state for new track
        currentTrack = TrackInfo(
            artist = artist,
            track = track,
            album = album,
            durationMs = durationMs
        )
        playbackStartTime = System.currentTimeMillis()
        totalPlayedMs = 0
        isPaused = false

        Logger.d(TAG, "Track started: $artist - $track")

        // Update Now Playing
        scope.launch {
            updateNowPlaying()
        }
    }

    /**
     * Called when playback is paused.
     * Tracks the accumulated play time.
     */
    fun onPlaybackPause() {
        if (!isPaused && currentTrack != null) {
            totalPlayedMs += System.currentTimeMillis() - playbackStartTime
            isPaused = true
            Logger.d(TAG, "Playback paused. Total played: ${totalPlayedMs}ms")
        }
    }

    /**
     * Called when playback resumes.
     */
    fun onPlaybackResume() {
        if (isPaused && currentTrack != null) {
            playbackStartTime = System.currentTimeMillis()
            isPaused = false
            Logger.d(TAG, "Playback resumed")
        }
    }

    /**
     * Called when the track ends or is skipped.
     * Checks if scrobble criteria is met.
     */
    fun onTrackEnd() {
        checkAndScrobbleCurrentTrack()
        currentTrack = null
    }

    /**
     * Force check and scrobble the current track if eligible.
     */
    private fun checkAndScrobbleCurrentTrack() {
        val track = currentTrack ?: return

        // Calculate total played time
        val currentPlayedMs = if (!isPaused) {
            totalPlayedMs + (System.currentTimeMillis() - playbackStartTime)
        } else {
            totalPlayedMs
        }

        Logger.d(TAG, "Checking scrobble eligibility: played ${currentPlayedMs}ms of ${track.durationMs}ms")

        // Scrobble criteria:
        // 1. Track is longer than 30 seconds
        // 2. Track has been played for at least 4 minutes OR half its total length
        val minimumPlayTime = minOf(4 * 60 * 1000L, track.durationMs / 2)

        if (track.durationMs > 30_000 && currentPlayedMs >= minimumPlayTime) {
            Logger.d(TAG, "Track eligible for scrobble: ${track.artist} - ${track.track}")
            scope.launch {
                scrobbleTrack(track)
            }
        } else {
            Logger.d(TAG, "Track not eligible for scrobble")
        }
    }

    /**
     * Updates Now Playing status on Last.fm.
     */
    private suspend fun updateNowPlaying() {
        val track = currentTrack ?: return

        val apiKey = dataStoreManager.lastFmApiKey.first()
        val apiSecret = dataStoreManager.lastFmApiSecret.first()
        val sessionKey = dataStoreManager.lastFmSessionKey.first()
        val nowPlayingEnabled = dataStoreManager.lastFmNowPlayingEnabled.first() == DataStoreManager.Values.TRUE

        if (apiKey.isBlank() || apiSecret.isBlank() || sessionKey.isBlank()) {
            Logger.d(TAG, "Last.fm not configured")
            return
        }

        if (!nowPlayingEnabled) {
            Logger.d(TAG, "Now Playing disabled")
            return
        }

        val result = lastFmApi.updateNowPlaying(
            apiKey = apiKey,
            apiSecret = apiSecret,
            sessionKey = sessionKey,
            artist = track.artist,
            track = track.track,
            album = track.album,
            duration = (track.durationMs / 1000).toInt()
        )

        result.onSuccess {
            Logger.d(TAG, "Now Playing updated successfully")
        }.onFailure { e ->
            Logger.e(TAG, "Failed to update Now Playing: ${e.message}")
        }
    }

    /**
     * Scrobbles a track to Last.fm.
     */
    private suspend fun scrobbleTrack(track: TrackInfo) {
        val apiKey = dataStoreManager.lastFmApiKey.first()
        val apiSecret = dataStoreManager.lastFmApiSecret.first()
        val sessionKey = dataStoreManager.lastFmSessionKey.first()
        val scrobbleEnabled = dataStoreManager.lastFmScrobbleEnabled.first() == DataStoreManager.Values.TRUE

        if (apiKey.isBlank() || apiSecret.isBlank() || sessionKey.isBlank()) {
            Logger.d(TAG, "Last.fm not configured")
            return
        }

        if (!scrobbleEnabled) {
            Logger.d(TAG, "Scrobbling disabled")
            return
        }

        val scrobbleTrack = ScrobbleTrack(
            artist = track.artist,
            track = track.track,
            album = track.album,
            timestamp = System.currentTimeMillis() / 1000,
            duration = track.durationMs / 1000
        )

        val result = lastFmApi.scrobble(
            apiKey = apiKey,
            apiSecret = apiSecret,
            sessionKey = sessionKey,
            scrobbleTrack = scrobbleTrack
        )

        result.onSuccess {
            Logger.d(TAG, "Track scrobbled successfully: ${track.artist} - ${track.track}")
        }.onFailure { e ->
            Logger.e(TAG, "Failed to scrobble, caching for later: ${e.message}")
            cachePendingScrobble(scrobbleTrack)
        }
    }

    /**
     * Cache a failed scrobble for later retry.
     */
    private suspend fun cachePendingScrobble(track: ScrobbleTrack) {
        val currentPending = dataStoreManager.lastFmPendingScrobbles.first()
        val pendingList = try {
            json.decodeFromString<List<ScrobbleTrack>>(currentPending).toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }

        pendingList.add(track)

        // Keep only last 100 pending scrobbles
        while (pendingList.size > 100) {
            pendingList.removeAt(0)
        }

        dataStoreManager.setLastFmPendingScrobbles(json.encodeToString(pendingList))
        Logger.d(TAG, "Cached pending scrobble. Total pending: ${pendingList.size}")
    }

    /**
     * Retry all pending scrobbles.
     */
    suspend fun retryPendingScrobbles() {
        val apiKey = dataStoreManager.lastFmApiKey.first()
        val apiSecret = dataStoreManager.lastFmApiSecret.first()
        val sessionKey = dataStoreManager.lastFmSessionKey.first()

        if (apiKey.isBlank() || apiSecret.isBlank() || sessionKey.isBlank()) {
            Logger.d(TAG, "Last.fm not configured, skipping retry")
            return
        }

        val currentPending = dataStoreManager.lastFmPendingScrobbles.first()
        val pendingList = try {
            json.decodeFromString<List<ScrobbleTrack>>(currentPending)
        } catch (e: Exception) {
            emptyList()
        }

        if (pendingList.isEmpty()) {
            Logger.d(TAG, "No pending scrobbles")
            return
        }

        Logger.d(TAG, "Retrying ${pendingList.size} pending scrobbles")

        // Batch scrobble (max 50 at a time)
        val batches = pendingList.chunked(50)
        val successfullyScrobbled = mutableListOf<ScrobbleTrack>()

        for (batch in batches) {
            val result = lastFmApi.scrobbleBatch(
                apiKey = apiKey,
                apiSecret = apiSecret,
                sessionKey = sessionKey,
                tracks = batch
            )

            result.onSuccess { count ->
                if (count == batch.size) {
                    successfullyScrobbled.addAll(batch)
                }
                Logger.d(TAG, "Batch scrobbled: $count/${batch.size}")
            }.onFailure { e ->
                Logger.e(TAG, "Batch scrobble failed: ${e.message}")
            }
        }

        // Remove successfully scrobbled from pending
        if (successfullyScrobbled.isNotEmpty()) {
            val remaining = pendingList - successfullyScrobbled.toSet()
            dataStoreManager.setLastFmPendingScrobbles(json.encodeToString(remaining))
            Logger.d(TAG, "Cleared ${successfullyScrobbled.size} scrobbles. ${remaining.size} still pending.")
        }
    }

    companion object {
        private const val TAG = "LastFmManager"
    }
}
