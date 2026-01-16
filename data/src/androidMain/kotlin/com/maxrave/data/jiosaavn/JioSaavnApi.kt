package com.maxrave.data.jiosaavn

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * JioSaavn API client for searching and fetching music.
 *
 * Based on BloomeeTunes implementation with DES decryption for media URLs.
 */
class JioSaavnApi(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    companion object {
        private const val BASE_URL = "https://www.jiosaavn.com"
        private const val API_URL = "https://www.jiosaavn.com/api.php"
        
        // DES key for decrypting encrypted media URLs
        private const val DES_KEY = "38346591"
    }

    /**
     * Search for songs on JioSaavn
     */
    suspend fun searchSongs(query: String, page: Int = 1, limit: Int = 20): Result<List<JioSaavnSong>> {
        return try {
            val response = httpClient.get(API_URL) {
                parameter("__call", "search.getResults")
                parameter("p", page)
                parameter("q", query)
                parameter("n", limit)
                parameter("_format", "json")
                parameter("_marker", "0")
                parameter("ctx", "wap6dot0")
            }

            val jsonResponse = json.parseToJsonElement(response.body<String>()).jsonObject
            val results = jsonResponse["results"]?.jsonArray ?: return Result.success(emptyList())

            val songs = results.mapNotNull { item ->
                try {
                    formatSong(item.jsonObject)
                } catch (e: Exception) {
                    null
                }
            }

            Result.success(songs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Search for albums on JioSaavn
     */
    suspend fun searchAlbums(query: String, page: Int = 1, limit: Int = 20): Result<List<JioSaavnAlbum>> {
        return try {
            val response = httpClient.get(API_URL) {
                parameter("__call", "search.getAlbumResults")
                parameter("p", page)
                parameter("q", query)
                parameter("n", limit)
                parameter("_format", "json")
                parameter("_marker", "0")
                parameter("ctx", "wap6dot0")
            }

            val jsonResponse = json.parseToJsonElement(response.body<String>()).jsonObject
            val results = jsonResponse["results"]?.jsonArray ?: return Result.success(emptyList())

            val albums = results.mapNotNull { item ->
                try {
                    formatAlbum(item.jsonObject)
                } catch (e: Exception) {
                    null
                }
            }

            Result.success(albums)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get song details by ID
     */
    suspend fun getSongDetails(id: String): Result<JioSaavnSong?> {
        return try {
            val response = httpClient.get(API_URL) {
                parameter("__call", "song.getDetails")
                parameter("pids", id)
                parameter("_format", "json")
                parameter("_marker", "0")
                parameter("ctx", "wap6dot0")
            }

            val jsonResponse = json.parseToJsonElement(response.body<String>()).jsonObject
            val songs = jsonResponse["songs"]?.jsonArray
            
            if (songs.isNullOrEmpty()) {
                return Result.success(null)
            }

            val song = formatSong(songs[0].jsonObject)
            Result.success(song)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get album details by ID
     */
    suspend fun getAlbumDetails(id: String): Result<JioSaavnAlbumDetails?> {
        return try {
            val response = httpClient.get(API_URL) {
                parameter("__call", "content.getAlbumDetails")
                parameter("albumid", id)
                parameter("_format", "json")
                parameter("_marker", "0")
                parameter("ctx", "wap6dot0")
            }

            val jsonResponse = json.parseToJsonElement(response.body<String>()).jsonObject
            
            val album = formatAlbum(jsonResponse)
            val songs = jsonResponse["list"]?.jsonArray?.mapNotNull { 
                try { formatSong(it.jsonObject) } catch (e: Exception) { null }
            } ?: emptyList()

            Result.success(JioSaavnAlbumDetails(album, songs))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get playlist details by ID
     */
    suspend fun getPlaylistDetails(id: String): Result<JioSaavnPlaylist?> {
        return try {
            val response = httpClient.get(API_URL) {
                parameter("__call", "playlist.getDetails")
                parameter("listid", id)
                parameter("_format", "json")
                parameter("_marker", "0")
                parameter("ctx", "wap6dot0")
            }

            val jsonResponse = json.parseToJsonElement(response.body<String>()).jsonObject
            
            val name = jsonResponse["listname"]?.jsonPrimitive?.content ?: ""
            val image = getImageUrl(jsonResponse["image"]?.jsonPrimitive?.content)
            val songs = jsonResponse["songs"]?.jsonArray?.mapNotNull { 
                try { formatSong(it.jsonObject) } catch (e: Exception) { null }
            } ?: emptyList()

            Result.success(JioSaavnPlaylist(id, name, image, songs))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get stream URL for a song.
     * Decrypts the encrypted_media_url using DES.
     */
    suspend fun getStreamUrl(song: JioSaavnSong, quality: String = "320kbps"): Result<String> {
        return try {
            val encryptedUrl = song.encryptedMediaUrl
            if (encryptedUrl.isNullOrBlank()) {
                return Result.failure(Exception("No encrypted URL available"))
            }

            val decryptedUrl = decryptUrl(encryptedUrl)
            
            // Replace quality in URL
            val qualityUrl = when (quality) {
                "320kbps" -> decryptedUrl.replace("_96.mp4", "_320.mp4")
                "160kbps" -> decryptedUrl.replace("_96.mp4", "_160.mp4")
                else -> decryptedUrl
            }

            Result.success(qualityUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get recommendations based on a song ID.
     * Creates a radio station and fetches similar tracks.
     */
    suspend fun getRecommendations(songId: String, limit: Int = 10): Result<List<JioSaavnSong>> {
        return try {
            // First, get the station ID
            val stationId = getStation(songId)
            if (stationId.isNullOrBlank()) {
                return Result.failure(Exception("Failed to create station"))
            }

            // Fetch recommendations from the station
            val response = httpClient.get(API_URL) {
                parameter("__call", "webradio.getSong")
                parameter("api_version", "4")
                parameter("_format", "json")
                parameter("_marker", "0")
                parameter("ctx", "android")
                parameter("stationid", stationId)
                parameter("k", limit)
            }

            val jsonResponse = json.parseToJsonElement(response.body<String>()).jsonObject
            
            val songs = mutableListOf<JioSaavnSong>()
            for ((key, value) in jsonResponse) {
                try {
                    val songData = value.jsonObject["song"]?.jsonObject
                    if (songData != null) {
                        songs.add(formatSong(songData))
                    }
                } catch (e: Exception) {
                    // Skip invalid entries
                }
            }

            Result.success(songs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Creates a radio station based on a song identifier and returns the station ID.
     * Used internally for fetching recommendations.
     */
    private suspend fun getStation(songId: String): String? {
        return try {
            // Encode the song ID as JSON array
            val encodedSongId = "[\"${java.net.URLEncoder.encode(songId, "UTF-8")}\"]"
            
            val response = httpClient.get(API_URL) {
                parameter("__call", "webradio.createEntityStation")
                parameter("api_version", "4")
                parameter("_format", "json")
                parameter("_marker", "0")
                parameter("ctx", "android")
                parameter("entity_id", encodedSongId)
                parameter("entity_type", "queue")
            }

            val jsonResponse = json.parseToJsonElement(response.body<String>()).jsonObject
            jsonResponse["stationid"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }

    // --- Formatting Functions ---

    private fun formatSong(data: JsonObject): JioSaavnSong {
        val id = data["id"]?.jsonPrimitive?.content ?: ""
        val title = decode(data["song"]?.jsonPrimitive?.content ?: data["title"]?.jsonPrimitive?.content ?: "")
        val album = decode(data["album"]?.jsonPrimitive?.content ?: "")
        val artists = data["primary_artists"]?.jsonPrimitive?.content 
            ?: data["singers"]?.jsonPrimitive?.content 
            ?: ""
        val artistList = decode(artists).split(", ").filter { it.isNotBlank() }
        val image = getImageUrl(data["image"]?.jsonPrimitive?.content)
        val duration = data["duration"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val encryptedUrl = data["encrypted_media_url"]?.jsonPrimitive?.content
        val year = data["year"]?.jsonPrimitive?.content ?: ""
        val language = data["language"]?.jsonPrimitive?.content ?: ""

        return JioSaavnSong(
            id = id,
            title = title,
            album = album,
            artists = artistList,
            image = image,
            duration = duration,
            encryptedMediaUrl = encryptedUrl,
            year = year,
            language = language
        )
    }

    private fun formatAlbum(data: JsonObject): JioSaavnAlbum {
        val id = data["albumid"]?.jsonPrimitive?.content 
            ?: data["id"]?.jsonPrimitive?.content 
            ?: ""
        val title = decode(data["title"]?.jsonPrimitive?.content 
            ?: data["album"]?.jsonPrimitive?.content 
            ?: "")
        val artists = decode(data["primary_artists"]?.jsonPrimitive?.content 
            ?: data["music"]?.jsonPrimitive?.content 
            ?: "")
        val image = getImageUrl(data["image"]?.jsonPrimitive?.content)
        val year = data["year"]?.jsonPrimitive?.content ?: ""
        val songCount = data["more_info"]?.jsonObject?.get("song_count")?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        return JioSaavnAlbum(
            id = id,
            title = title,
            artists = artists,
            image = image,
            year = year,
            songCount = songCount
        )
    }

    // --- Utility Functions ---

    /**
     * Decode HTML entities in strings
     */
    private fun decode(text: String): String {
        return text
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    /**
     * Get high quality image URL
     */
    private fun getImageUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return url
            .replace("150x150", "500x500")
            .replace("50x50", "500x500")
    }

    /**
     * Decrypt the encrypted media URL using DES
     */
    private fun decryptUrl(encryptedUrl: String): String {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        val keySpec = SecretKeySpec(DES_KEY.toByteArray(), "DES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec)

        val encryptedBytes = android.util.Base64.decode(encryptedUrl, android.util.Base64.DEFAULT)
        val decryptedBytes = cipher.doFinal(encryptedBytes)

        return String(decryptedBytes, Charsets.UTF_8)
    }
}

/**
 * Data class representing a JioSaavn song
 */
data class JioSaavnSong(
    val id: String,
    val title: String,
    val album: String,
    val artists: List<String>,
    val image: String,
    val duration: Int, // in seconds
    val encryptedMediaUrl: String?,
    val year: String,
    val language: String,
)

/**
 * Data class representing a JioSaavn album
 */
data class JioSaavnAlbum(
    val id: String,
    val title: String,
    val artists: String,
    val image: String,
    val year: String,
    val songCount: Int,
)

/**
 * Data class representing album details with songs
 */
data class JioSaavnAlbumDetails(
    val album: JioSaavnAlbum,
    val songs: List<JioSaavnSong>,
)

/**
 * Data class representing a JioSaavn playlist
 */
data class JioSaavnPlaylist(
    val id: String,
    val name: String,
    val image: String,
    val songs: List<JioSaavnSong>,
)
