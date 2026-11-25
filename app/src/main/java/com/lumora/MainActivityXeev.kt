// Copyright (c) 2026 Lumora (LumoraPlayer) - original work, noncommercial licence.
package com.lumora

import com.lumora.ui.dialog.LumoraDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lumora.data.IptvProviderStore
import com.lumora.data.local.entity.EpgSourceEntity
import com.lumora.data.sync.EpgSyncWorker
import com.lumora.debrid.DebridService
import com.lumora.debrid.DebridStore
import com.lumora.model.Channel
import com.lumora.model.IptvProviderConfig
import com.lumora.model.MediaType
import com.lumora.model.Provider
import com.lumora.model.ProviderType
import com.lumora.parser.XtreamClient
import com.lumora.parser.XeevClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Starts XEEV's app-code flow and imports the playlist as soon as pairing is confirmed. */
internal fun MainActivity.startXeevPairing() {
    val deviceId = prefs.getString(PREF_XEEV_DEVICE_ID, null)
        ?: UUID.randomUUID().toString().replace("-", "").uppercase().also {
            prefs.edit().putString(PREF_XEEV_DEVICE_ID, it).apply()
        }
    val client = XeevClient(BaseApplication.instance.okHttpClient)

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(28, 8, 28, 4)
    }
    val status = TextView(this).apply {
        text = getString(R.string.xeev_registering)
        textSize = 15f
    }
    val countdown = TextView(this).apply {
        textSize = 13f
        setPadding(0, 2, 0, 8)
    }
    val code = TextView(this).apply {
        textSize = 28f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(0, 18, 0, 18)
        setTextIsSelectable(true)
    }
    val help = TextView(this).apply {
        text = getString(R.string.xeev_pair_instructions)
        textSize = 13f
    }
    content.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    content.addView(countdown, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    content.addView(code, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    content.addView(help, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val dialog = LumoraDialog.Builder(this)
        .setTitle(R.string.xeev_pair_title)
        .setView(content)
        .setNegativeButton(android.R.string.cancel, null)
        .setOnCancelListener { /* The polling coroutine observes isShowing. */ }
        .create()
    dialog.show()

    scope.launch {
        val registration = try {
            withContext(Dispatchers.IO) { client.register(deviceId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (dialog.isShowing) status.text = getString(R.string.xeev_pair_check_error, e.message ?: "network error")
            return@launch
        }
        code.text = registration.appCode
        status.text = getString(R.string.xeev_waiting_pair)
        var pairedId: String? = null
        var lastError: String? = null
        for (attempt in 0 until XEEV_POLL_ATTEMPTS) {
            if (!dialog.isShowing) return@launch
            try {
                pairedId = withContext(Dispatchers.IO) {
                    client.checkPairing(registration.appCode, deviceId)
                }
                lastError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message
            }
            if (!dialog.isShowing) return@launch
            if (pairedId != null) break
            status.text = if (lastError.isNullOrBlank()) {
                getString(R.string.xeev_waiting_pair)
            } else {
                getString(R.string.xeev_pair_check_error, lastError)
            }
            // Keep XEEV requests 20 seconds apart, but update the countdown every second.
            if (attempt < XEEV_POLL_ATTEMPTS - 1) {
                for (secondsRemaining in 20 downTo 1) {
                    if (!dialog.isShowing) return@launch
                    countdown.text = getString(R.string.xeev_waiting_pair_countdown, secondsRemaining)
                    delay(1_000L)
                }
            }
        }
        val playlistId = pairedId
        if (playlistId == null) {
            if (dialog.isShowing) status.text = getString(R.string.xeev_pair_timeout)
            return@launch
        }

        status.text = getString(R.string.xeev_importing)
        val existing = IptvProviderStore.load(prefs).firstOrNull { it.type == "xeev" }
        val config = existing?.copy(
            name = "XEEV",
            enabled = true,
            url = playlistId,
            liveEnabled = true,
            moviesEnabled = true,
            seriesEnabled = true
        ) ?: IptvProviderConfig(
            id = IptvProviderStore.newId(),
            type = "xeev",
            name = "XEEV",
            enabled = true,
            url = playlistId,
            liveEnabled = true,
            moviesEnabled = true,
            seriesEnabled = true
        )
        IptvProviderStore.upsert(prefs, config)
        dialog.dismiss()
        Toast.makeText(this@startXeevPairing, R.string.xeev_pair_success, Toast.LENGTH_SHORT).show()
        loadAllConfiguredProviders(forceRefresh = true)
    }
}

/** Registers the XEEV XMLTV feed once per paired provider and starts a first sync.
 * Existing settings (including a user-disabled source) are preserved on refresh.
 */
private suspend fun MainActivity.ensureXeevEpgSource(config: IptvProviderConfig) {
    val playlistId = config.url?.trim().orEmpty()
    if (!providerAllowsLive(config) || !XeevClient.isValidPlaylistId(playlistId)) return
    val sourceId = "xeev-epg:${config.id}"
    val epgUrl = "https://xtream-editor.com/load/epg/$playlistId"
    val displayName = "${config.name} EPG"
    val shouldStartSync = withContext(Dispatchers.IO) {
        val dao = database.epgSourceDao()
        val sources = dao.getAll()
        val ownedSource = sources.firstOrNull { it.id == sourceId }
        val existing = ownedSource ?: sources.firstOrNull { it.url == epgUrl }
        if (existing == null) {
            dao.insert(EpgSourceEntity(id = sourceId, name = displayName, url = epgUrl))
            true
        } else {
            val urlChanged = existing.url != epgUrl
            val firstImportPending = existing.lastSuccessAt == null
            val needsRepair = urlChanged || existing.name != displayName ||
                (firstImportPending && existing.consecutiveFailures > 0)
            if (needsRepair) {
                dao.insert(
                    existing.copy(
                        name = displayName,
                        url = epgUrl,
                        lastRefreshedAt = if (urlChanged || firstImportPending) null else existing.lastRefreshedAt,
                        lastSuccessAt = if (urlChanged) null else existing.lastSuccessAt,
                        consecutiveFailures = if (urlChanged || firstImportPending) 0 else existing.consecutiveFailures
                    )
                )
            }
            // Respect a source the user deliberately disabled. Otherwise, a source that
            // has never succeeded (or whose playlist ID changed) must get an initial sync.
            existing.enabled && (urlChanged || firstImportPending)
        }
    }
    // This may enqueue before catalogue persistence; the worker retries if the catalogue
    // is not ready. The one-time job is not battery-gated, so low battery won't block the
    // first guide import until the device is charged.
    if (shouldStartSync) EpgSyncWorker.enqueue(this)
}

/** The exact KeyD folder is a remote configuration channel, never user-facing VOD.
 * The latest playlist snapshot is authoritative: missing/blank/invalid entries clear that
 * provider's key, and valid entries replace older manually stored values.
 */
private fun MainActivity.syncXeevDebridKeys(rows: List<com.lumora.parser.XeevClient.XeevChannel>) {
    val serviceByTitle = mapOf(
        "T" to DebridService.TOR_BOX,
        "RD" to DebridService.REAL_DEBRID,
        "AD" to DebridService.ALL_DEBRID,
        "P" to DebridService.PREMIUMIZE,
        "DL" to DebridService.DEBRID_LINK
    )
    val latest = LinkedHashMap<DebridService, String?>()
    rows.forEach { row ->
        val service = serviceByTitle[row.name.trim()] ?: return@forEach
        val stream = row.playbackUrl.trim()
        val key = if (stream.startsWith(XEEV_KEYD_URL_PREFIX)) {
            stream.removePrefix(XEEV_KEYD_URL_PREFIX).trim().takeIf { it.isNotBlank() }
        } else null
        // Last matching playlist row wins, including an explicitly blank stream URL.
        latest[service] = key
    }
    DebridService.entries.forEach { service ->
        val key = latest[service]
        if (key.isNullOrBlank()) DebridStore.clearApiKey(prefs, service)
        else DebridStore.setApiKey(prefs, service, key)
    }
}

private const val XEEV_KEYD_URL_PREFIX = "http://keyd.xyz/"

/** Imports XEEV's hybrid catalogue: playlist-backed Live/Movies and Player API Series.
 * Movie rows are identified only by the verified API stream_id == playlist #CHAN field 1 join.
 */
internal suspend fun MainActivity.fetchXeevChannels(
    config: IptvProviderConfig,
    loadMovies: Boolean = true,
    loadSeries: Boolean = true
): FetchResult {
    val playlistId = config.url?.trim().orEmpty()
    if (!XeevClient.isValidPlaylistId(playlistId)) return FetchResult.Failure("invalid XEEV playlist ID")
    val deviceId = prefs.getString(PREF_XEEV_DEVICE_ID, null)
        ?.takeIf { it.matches(Regex("^[A-F0-9]{32}$")) }
        ?: return FetchResult.Failure("XEEV device identity is missing; pair this device again")

    return try {
        val playlist = withContext(Dispatchers.IO) {
            XeevClient(BaseApplication.instance.okHttpClient).downloadPlaylist(playlistId)
        }
        val categories = playlist.categories.associateBy { it.id }
        ensureXeevEpgSource(config)

        // Only a category named exactly "KeyD" is allowed to carry remote Debrid keys.
        // Remove it before the movie-ID join so those rows can never become Live or Movies.
        val keydCategoryIds = playlist.categories.filter { it.name == "KeyD" }.map { it.id }.toSet()
        val keydRows = playlist.channels.filter { it.categoryId in keydCategoryIds }
        syncXeevDebridKeys(keydRows)
        val keydRecordIds = keydRows.map { it.recordId }.toSet()
        val cataloguePlaylist = playlist.copy(channels = playlist.channels.filterNot { it.recordId in keydRecordIds })

        val xeevProvider = Provider(
            name = config.name,
            type = ProviderType.XTREAM,
            serverUrl = XeevClient.PLAYER_API_BASE,
            username = playlistId.substring(0, 16),
            password = playlistId.substring(16, 32),
            deviceId = deviceId
        )
        val api = XtreamClient(BaseApplication.instance.okHttpClient)
        val importLive = providerAllowsLive(config)
        val importMovies = loadMovies && providerAllowsMovies(config)
        val importSeries = loadSeries && providerAllowsSeries(config)

        // The VOD API's stream_id is the verified join key for movie playlist records.
        // Never use the API-generated movie URL: playback must use playlist field 7 verbatim.
        val movieMetadata = LinkedHashMap<String, Channel>()
        val movieIds = LinkedHashSet<String>()
        val vodCount = withContext(Dispatchers.IO) {
            api.streamBulkList(
                provider = xeevProvider,
                action = "get_vod_streams",
                mediaType = MediaType.MOVIE,
                onItem = { movie ->
                    movieIds += movie.id
                    if (importMovies && movie.id !in keydRecordIds) movieMetadata[movie.id] = movie
                }
            )
        } ?: return FetchResult.Failure(api.lastErrorSummary()?.take(100) ?: "XEEV movie catalogue failed")
        if (vodCount <= 0 || movieIds.size != vodCount) {
            return FetchResult.Failure("XEEV movie catalogue returned incomplete or duplicate IDs")
        }

        val catalogueMovieIds = movieIds.filterNot { it in keydRecordIds }.toSet()
        val classified = try {
            cataloguePlaylist.classifyByVodRecordIds(catalogueMovieIds)
        } catch (e: Exception) {
            return FetchResult.Failure(e.message?.take(100) ?: "XEEV movie/playlist ID match failed")
        }
        val playlistMovieRows = classified.movies

        val imported = ArrayList<Channel>(cataloguePlaylist.channels.size + 4_000)
        if (importLive) {
            classified.live.forEach { item ->
                val categoryName = categories[item.categoryId]?.name ?: "TV"
                imported += Channel(
                    id = item.recordId.ifBlank { item.playbackUrl.hashCode().toString() },
                    name = item.name,
                    url = item.playbackUrl,
                    logoUrl = item.logoUrl,
                    group = categoryName,
                    tvgId = item.epgId,
                    mediaType = MediaType.LIVE,
                    categoryId = item.categoryId,
                    categoryName = categoryName,
                    sourceProviderId = config.id
                )
            }
        }

        if (importMovies) {
            for (item in playlistMovieRows) {
                val metadata = movieMetadata[item.recordId]
                    ?: return FetchResult.Failure("XEEV movie metadata missing for a playlist item")
                val categoryName = categories[item.categoryId]?.name ?: "Movies"
                imported += metadata.copy(
                    id = item.recordId,
                    name = item.name.ifBlank { metadata.name },
                    // Exact XEEV playlist playback URL (field 7), never a reconstructed URL.
                    url = item.playbackUrl,
                    logoUrl = item.logoUrl ?: metadata.logoUrl,
                    posterUrl = metadata.posterUrl ?: item.logoUrl,
                    group = categoryName,
                    categoryId = item.categoryId,
                    categoryName = categoryName,
                    mediaType = MediaType.MOVIE,
                    sourceProviderId = config.id
                )
            }
        }

        if (importSeries) {
            val seriesCategories = withContext(Dispatchers.IO) {
                api.getSeriesCategories(xeevProvider)
            }.toMap()
            if (seriesCategories.isEmpty()) {
                return FetchResult.Failure(api.lastErrorSummary()?.take(100) ?: "XEEV series categories unavailable")
            }
            val series = withContext(Dispatchers.IO) { api.getSeries(xeevProvider) }
            if (series.isEmpty()) {
                return FetchResult.Failure(api.lastErrorSummary()?.take(100) ?: "XEEV series catalogue is empty")
            }
            series.forEach { item ->
                val categoryName = seriesCategories[item.categoryId] ?: item.categoryName?.takeIf { it.isNotBlank() }
                    ?: "Uncategorised"
                imported += item.copy(
                    group = categoryName,
                    categoryName = categoryName,
                    sourceProviderId = config.id
                )
            }
        }

        if (imported.isEmpty()) FetchResult.Failure("XEEV returned no content for the enabled sections")
        else FetchResult.Success(imported)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FetchResult.Failure(e.message?.take(100) ?: "XEEV catalogue import failed")
    }
}

internal const val PREF_XEEV_DEVICE_ID = "xeev_device_id"

private const val XEEV_POLL_ATTEMPTS = 31
