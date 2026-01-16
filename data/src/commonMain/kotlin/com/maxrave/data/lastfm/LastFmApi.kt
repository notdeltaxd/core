package com.maxrave.data.lastfm

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * Last.fm API client for authentication, Now Playing updates, and scrobbling.
 *
 * Implements the Last.fm 2.0 API with proper MD5 signature generation.
 */
class LastFmApi(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    companion object {
        private const val API_URL = "https://ws.audioscrobbler.com/2.0/"
        private const val AUTH_URL = "https://www.last.fm/api/auth/"
    }

    /**
     * Get the authentication URL for the user to authorize the application.
     */
    fun getAuthUrl(apiKey: String): String {
        return "${AUTH_URL}?api_key=$apiKey"
    }

    /**
     * Get a session key using the auth token obtained from the web authentication flow.
     */
    suspend fun getSession(
        apiKey: String,
        apiSecret: String,
        token: String
    ): Result<Pair<String, String>> { // Returns (sessionKey, username)
        return try {
            val params = mapOf(
                "method" to "auth.getSession",
                "api_key" to apiKey,
                "token" to token,
            )
            val signature = generateSignature(params, apiSecret)

            val response = httpClient.submitForm(
                url = API_URL,
                formParameters = Parameters.build {
                    params.forEach { (key, value) -> append(key, value) }
                    append("api_sig", signature)
                    append("format", "json")
                }
            )

            val jsonResponse = json.parseToJsonElement(response.body<String>()).jsonObject
            
            if (jsonResponse.containsKey("error")) {
                return Result.failure(Exception(jsonResponse["message"]?.jsonPrimitive?.content ?: "Unknown error"))
            }

            val session = jsonResponse["session"]?.jsonObject
            val key = session?.get("key")?.jsonPrimitive?.content
            val name = session?.get("name")?.jsonPrimitive?.content

            if (key != null && name != null) {
                Result.success(key to name)
            } else {
                Result.failure(Exception("Failed to parse session response"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Update the "Now Playing" status on Last.fm.
     */
    suspend fun updateNowPlaying(
        apiKey: String,
        apiSecret: String,
        sessionKey: String,
        artist: String,
        track: String,
        album: String? = null,
        duration: Int? = null
    ): Result<Boolean> {
        return try {
            val params = mutableMapOf(
                "method" to "track.updateNowPlaying",
                "api_key" to apiKey,
                "sk" to sessionKey,
                "artist" to artist,
                "track" to track,
            )
            album?.let { params["album"] = it }
            duration?.let { params["duration"] = it.toString() }

            val signature = generateSignature(params, apiSecret)

            val response = httpClient.submitForm(
                url = API_URL,
                formParameters = Parameters.build {
                    params.forEach { (key, value) -> append(key, value) }
                    append("api_sig", signature)
                    append("format", "json")
                }
            )

            val jsonResponse = json.parseToJsonElement(response.body<String>()).jsonObject
            
            if (jsonResponse.containsKey("error")) {
                return Result.failure(Exception(jsonResponse["message"]?.jsonPrimitive?.content ?: "Unknown error"))
            }

            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Scrobble a track to Last.fm.
     *
     * Scrobbling should happen when:
     * - The track is longer than 30 seconds
     * - The track has been played for at least 4 minutes OR half its total length
     */
    suspend fun scrobble(
        apiKey: String,
        apiSecret: String,
        sessionKey: String,
        scrobbleTrack: ScrobbleTrack
    ): Result<Boolean> {
        return try {
            val params = mutableMapOf(
                "method" to "track.scrobble",
                "api_key" to apiKey,
                "sk" to sessionKey,
                "artist" to scrobbleTrack.artist,
                "track" to scrobbleTrack.track,
                "timestamp" to scrobbleTrack.timestamp.toString(),
            )
            scrobbleTrack.album?.let { params["album"] = it }
            scrobbleTrack.duration?.let { params["duration"] = it.toString() }
            scrobbleTrack.albumArtist?.let { params["albumArtist"] = it }
            scrobbleTrack.trackNumber?.let { params["trackNumber"] = it.toString() }

            val signature = generateSignature(params, apiSecret)

            val response = httpClient.submitForm(
                url = API_URL,
                formParameters = Parameters.build {
                    params.forEach { (key, value) -> append(key, value) }
                    append("api_sig", signature)
                    append("format", "json")
                }
            )

            val jsonResponse = json.parseToJsonElement(response.body<String>()).jsonObject
            
            if (jsonResponse.containsKey("error")) {
                return Result.failure(Exception(jsonResponse["message"]?.jsonPrimitive?.content ?: "Unknown error"))
            }

            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Scrobble multiple tracks at once (batch scrobble).
     */
    suspend fun scrobbleBatch(
        apiKey: String,
        apiSecret: String,
        sessionKey: String,
        tracks: List<ScrobbleTrack>
    ): Result<Int> { // Returns number of successfully scrobbled tracks
        if (tracks.isEmpty()) return Result.success(0)
        if (tracks.size > 50) {
            // Last.fm allows max 50 tracks per batch
            return Result.failure(Exception("Maximum 50 tracks per batch"))
        }

        return try {
            val params = mutableMapOf(
                "method" to "track.scrobble",
                "api_key" to apiKey,
                "sk" to sessionKey,
            )

            tracks.forEachIndexed { index, track ->
                params["artist[$index]"] = track.artist
                params["track[$index]"] = track.track
                params["timestamp[$index]"] = track.timestamp.toString()
                track.album?.let { params["album[$index]"] = it }
                track.duration?.let { params["duration[$index]"] = it.toString() }
            }

            val signature = generateSignature(params, apiSecret)

            val response = httpClient.submitForm(
                url = API_URL,
                formParameters = Parameters.build {
                    params.forEach { (key, value) -> append(key, value) }
                    append("api_sig", signature)
                    append("format", "json")
                }
            )

            val jsonResponse = json.parseToJsonElement(response.body<String>()).jsonObject
            
            if (jsonResponse.containsKey("error")) {
                return Result.failure(Exception(jsonResponse["message"]?.jsonPrimitive?.content ?: "Unknown error"))
            }

            // Count accepted scrobbles
            val scrobbles = jsonResponse["scrobbles"]?.jsonObject
            val accepted = scrobbles?.get("@attr")?.jsonObject?.get("accepted")?.jsonPrimitive?.content?.toIntOrNull() ?: 0

            Result.success(accepted)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Generate the API signature required for authenticated requests.
     * The signature is an MD5 hash of the sorted params + secret.
     */
    private fun generateSignature(params: Map<String, String>, apiSecret: String): String {
        val sortedParams = params.toSortedMap()
        val signatureBase = sortedParams.entries.joinToString("") { "${it.key}${it.value}" } + apiSecret
        return md5(signatureBase)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
