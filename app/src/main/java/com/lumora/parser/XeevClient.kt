// Copyright (c) 2026 Lumora (LumoraPlayer) - original work, noncommercial licence.
package com.lumora.parser

import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Small client for the observed XEEV app-code pairing and custom playlist endpoints. */
class XeevClient(private val http: OkHttpClient) {
    data class Registration(val appCode: String, val qrUrl: String?)
    data class Category(val id: String, val name: String)
    data class Playlist(val categories: List<Category>, val channels: List<XeevChannel>, val ignoredChannels: Int) {
        /** Split the shared #CHAN list using the verified VOD stream_id -> record ID join.
         * Every VOD ID must match exactly one playlist row before any remainder is called Live.
         */
        fun classifyByVodRecordIds(vodRecordIds: Set<String>): ClassifiedChannels {
            if (vodRecordIds.isEmpty()) throw IOException("XEEV VOD API returned no movie IDs")
            val rowsById = channels.groupBy { it.recordId }
            if (rowsById.any { (id, rows) -> id.isBlank() || rows.size != 1 }) {
                throw IOException("XEEV playlist contains blank or duplicate record IDs")
            }
            val movies = vodRecordIds.map { id ->
                val matches = rowsById[id]
                    ?: throw IOException("XEEV movie ID did not match a playlist record")
                if (matches.size != 1) throw IOException("XEEV movie ID matched multiple playlist records")
                matches.single()
            }
            return ClassifiedChannels(
                live = channels.filterNot { it.recordId in vodRecordIds },
                movies = movies
            )
        }
    }
    data class ClassifiedChannels(val live: List<XeevChannel>, val movies: List<XeevChannel>)
    data class XeevChannel(
        val recordId: String, val categoryId: String, val name: String,
        val epgId: String?, val logoUrl: String?, val playbackUrl: String,
        val providerStreamId: String? = null
    )

    @Throws(IOException::class)
    fun register(deviceId: String): Registration {
        val body = FormBody.Builder().add("deviceid", deviceId).build()
        val request = Request.Builder().url("$BASE/apk/get/newappid").post(body)
            .header("Accept", "application/json").build()
        val result = executeJson(request).get("xresult")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("XEEV did not return registration details")
        val code = result.stringValue("appid")?.trim()?.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{3,32}$")) }
            ?: throw IOException("XEEV did not return a valid app code")
        return Registration(code, result.stringValue("qrcode")?.takeIf { it.startsWith("https://") })
    }

    /** Null means the app code is not paired yet. */
    @Throws(IOException::class)
    fun checkPairing(appCode: String, deviceId: String): String? {
        val body = FormBody.Builder().add("version", APP_VERSION)
            .add("deviceid", deviceId).add("wakeup", "0").build()
        val request = Request.Builder().url("$BASE/apk/get/settings/${appCode.trim()}")
            .post(body).header("Accept", "application/json").build()
        val result = executeJson(request).get("xresult")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("XEEV returned no pairing status")
        val lineId = result.stringValue("lineid")?.trim()
            ?: throw IOException("XEEV returned no playlist ID")
        if (lineId.isBlank() || lineId == "0") return null
        if (!isValidPlaylistId(lineId)) throw IOException("XEEV returned an invalid playlist ID")
        return lineId
    }

    @Throws(IOException::class)
    fun downloadPlaylist(playlistId: String): Playlist {
        require(isValidPlaylistId(playlistId)) { "Invalid XEEV playlist ID" }
        val request = Request.Builder().url("$BASE/load/apk/$playlistId").get()
            .header("Accept", "text/plain").build()
        val text = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("XEEV playlist download failed (HTTP ${response.code})")
            val body = response.body ?: throw IOException("XEEV returned an empty playlist")
            if (body.contentLength() > MAX_PLAYLIST_BYTES) throw IOException("XEEV playlist is too large")
            body.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(16 * 1024)
                var total = 0L
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    total += count.toLong()
                    if (total > MAX_PLAYLIST_BYTES) throw IOException("XEEV playlist is too large")
                    out.append(buffer, 0, count)
                }
                out.toString()
            }
        }
        return parsePlaylist(text)
    }

    /** Parse the shared #CAT/#CHAN playlist. #CHAN rows are classified as Live or Movie
     * only after joining their record IDs to the Player API VOD stream IDs. */
    fun parsePlaylist(content: String): Playlist {
        val categories = LinkedHashMap<String, Category>()
        val channels = ArrayList<XeevChannel>()
        var ignored = 0
        content.lineSequence().forEach { raw ->
            val line = raw.removePrefix("\uFEFF").trimEnd('\r')
            if (line.isBlank() || !line.startsWith('#')) return@forEach
            val fields = line.split('\t')
            when (fields.firstOrNull()?.trim()) {
                "#CAT" -> {
                    val id = fields.getOrNull(1)?.trim().orEmpty()
                    val name = fields.getOrNull(2)?.trim().orEmpty()
                    if (id.isNotEmpty() && name.isNotEmpty()) categories[id] = Category(id, name)
                }
                "#CHAN" -> {
                    val recordId = fields.getOrNull(1)?.trim().orEmpty()
                    val name = fields.getOrNull(3)?.trim().orEmpty()
                    val categoryId = fields.getOrNull(2)?.trim().orEmpty()
                    // Keep field 7 byte-for-byte as supplied for playback. Trim only for validation.
                    val playback = fields.getOrNull(7).orEmpty()
                    val validUrl = playback.trim().toHttpUrlOrNull()?.let {
                        it.scheme == "http" || it.scheme == "https"
                    } == true
                    if (fields.size < 8 || recordId.isEmpty() || name.isEmpty() || categoryId.isEmpty() || !validUrl) {
                        ignored++
                    } else {
                        val primaryEpg = fields.getOrNull(4)?.trim().orEmpty()
                        val secondaryEpg = fields.getOrNull(5)?.trim().orEmpty()
                        channels += XeevChannel(
                            recordId = recordId,
                            categoryId = categoryId,
                            name = name.removeSuffix(" -").trim(),
                            epgId = primaryEpg.takeIf { it.isNotBlank() && it != "0" }
                                ?: secondaryEpg.takeIf { it.isNotBlank() && it != "0" },
                            logoUrl = fields.getOrNull(6)?.trim()?.takeIf { url ->
                                url.toHttpUrlOrNull()?.let { it.scheme == "http" || it.scheme == "https" } == true
                            },
                            playbackUrl = playback,
                            providerStreamId = fields.getOrNull(10)?.trim()?.takeIf { it.isNotBlank() }
                        )
                    }
                }
            }
        }
        if (channels.isEmpty()) throw IOException("XEEV playlist contained no supported #CHAN records")
        return Playlist(categories.values.toList(), channels, ignored)
    }

    private fun executeJson(request: Request) = http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("XEEV request failed (HTTP ${response.code})")
        val text = response.body?.string() ?: throw IOException("XEEV returned an empty response")
        try { JsonParser.parseString(text).asJsonObject }
        catch (_: Exception) { throw IOException("XEEV returned invalid JSON") }
    }

    private fun com.google.gson.JsonObject.stringValue(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }

    companion object {
        const val BASE = "https://xeev.app"
        /** X3M Player API host observed in the verified XEEV account tests. */
        const val PLAYER_API_BASE = "http://x3m.li"
        const val APP_VERSION = "2.6.0"
        private const val MAX_PLAYLIST_BYTES = 80L * 1024 * 1024
        fun isValidPlaylistId(value: String): Boolean = value.matches(Regex("^[A-Za-z0-9]{32}$"))
    }
}
