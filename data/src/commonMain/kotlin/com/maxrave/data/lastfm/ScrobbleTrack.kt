package com.maxrave.data.lastfm

import kotlinx.serialization.Serializable

/**
 * Represents a track to be scrobbled to Last.fm
 */
@Serializable
data class ScrobbleTrack(
    val artist: String,
    val track: String,
    val album: String? = null,
    val timestamp: Long,
    val duration: Long? = null, // in seconds
    val trackNumber: Int? = null,
    val albumArtist: String? = null,
    val mbid: String? = null, // MusicBrainz ID
)
