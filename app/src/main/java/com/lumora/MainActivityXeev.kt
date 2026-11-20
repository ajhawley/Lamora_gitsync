// Copyright (c) 2026 Lumora (LumoraPlayer) - original work, noncommercial licence.
package com.lumora

import com.lumora.ui.dialog.LumoraDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lumora.data.IptvProviderStore
import com.lumora.model.Channel
import com.lumora.model.IptvProviderConfig
import com.lumora.model.MediaType
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
    content.addView(code, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    content.addView(help, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val dialog = LumoraDialog.Builder(this)
        .setTitle(R.string.xeev_pair_title)
        .setView(content)
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(R.string.xeev_open_manager) { _, _ ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://xeev.app/"))) }
        }
        .setPositiveButton(R.string.xeev_copy_code) { _, _ ->
            val value = code.text.toString()
            if (value.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("XEEV app code", value))
                Toast.makeText(this, R.string.xeev_code_copied, Toast.LENGTH_SHORT).show()
            }
        }
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
            if (attempt > 0) delay(XEEV_POLL_INTERVAL_MS)
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
            val remaining = XEEV_POLL_ATTEMPTS - attempt - 1
            status.text = if (lastError.isNullOrBlank()) {
                getString(R.string.xeev_waiting_pair_countdown, remaining * 20 / 60, (remaining * 20) % 60)
            } else {
                getString(R.string.xeev_pair_check_error, lastError)
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
            moviesEnabled = false,
            seriesEnabled = false
        ) ?: IptvProviderConfig(
            id = IptvProviderStore.newId(),
            type = "xeev",
            name = "XEEV",
            enabled = true,
            url = playlistId,
            liveEnabled = true,
            moviesEnabled = false,
            seriesEnabled = false
        )
        IptvProviderStore.upsert(prefs, config)
        dialog.dismiss()
        Toast.makeText(this@startXeevPairing, R.string.xeev_pair_success, Toast.LENGTH_SHORT).show()
        loadAllConfiguredProviders(forceRefresh = true)
    }
}

/** XEEV custom format currently has verified live #CHAN rows only. */
internal suspend fun MainActivity.fetchXeevChannels(config: IptvProviderConfig): FetchResult {
    val playlistId = config.url?.trim().orEmpty()
    if (!XeevClient.isValidPlaylistId(playlistId)) return FetchResult.Failure("invalid XEEV playlist ID")
    return try {
        val playlist = withContext(Dispatchers.IO) {
            XeevClient(BaseApplication.instance.okHttpClient).downloadPlaylist(playlistId)
        }
        val categories = playlist.categories.associateBy { it.id }
        val channels = playlist.channels.map { item ->
            Channel(
                id = "xeev:${item.recordId.ifBlank { item.playbackUrl.hashCode().toString() }}",
                name = item.name,
                // XEEV field 7 is already the playable URL. Do not rebuild or rewrite it.
                url = item.playbackUrl,
                logoUrl = item.logoUrl,
                group = categories[item.categoryId]?.name ?: "TV",
                tvgId = item.epgId,
                mediaType = MediaType.LIVE,
                categoryId = item.categoryId,
                categoryName = categories[item.categoryId]?.name ?: "TV",
                sourceProviderId = config.id
            )
        }.filter { providerAllowsLive(config) }
        if (channels.isEmpty()) FetchResult.Failure("no live channels in XEEV playlist")
        else FetchResult.Success(channels)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FetchResult.Failure(e.message?.take(100) ?: "XEEV playlist download failed")
    }
}

private const val PREF_XEEV_DEVICE_ID = "xeev_device_id"
private const val XEEV_POLL_INTERVAL_MS = 20_000L
private const val XEEV_POLL_ATTEMPTS = 31
