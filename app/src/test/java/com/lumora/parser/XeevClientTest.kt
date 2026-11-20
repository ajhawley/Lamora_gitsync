package com.lumora.parser

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XeevClientTest {
    private val client = XeevClient(OkHttpClient())

    @Test fun parsesVerifiedCategoryAndLiveChannelFields() {
        val playback = "https://stream.example/live/123?token=a%2Fb"
        val text = listOf(
            "#PL\tPlaylist",
            "#CAT\tcat-1\tUK | Entertainment",
            listOf("#CHAN", "record-7", "cat-1", "BBC One HD", "0", "BBC1",
                "https://logos.example/bbc.png", playback, "0", "", "provider-123").joinToString("\t"),
            "#UNKNOWN\tignored"
        ).joinToString("\n")

        val parsed = client.parsePlaylist(text)

        assertEquals(1, parsed.categories.size)
        assertEquals("UK | Entertainment", parsed.categories.single().name)
        assertEquals(1, parsed.channels.size)
        assertEquals("BBC One HD", parsed.channels.single().name)
        assertEquals("BBC1", parsed.channels.single().epgId)
        assertEquals(playback, parsed.channels.single().playbackUrl)
        assertEquals("https://logos.example/bbc.png", parsed.channels.single().logoUrl)
    }

    @Test fun skipsMalformedLiveRowsAndDoesNotGuessVodRecords() {
        val text = listOf(
            "#CAT\tcat-1\tNews",
            "#CHAN\tbad\tcat-1\tMissing URL\t0\tNEWS\t\t",
            "#MOVIE\tunknown layout",
            "#SERIES\tunknown layout"
        ).joinToString("\n")
        val failure = runCatching { client.parsePlaylist(text) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("no supported live channels") == true)
    }
}
