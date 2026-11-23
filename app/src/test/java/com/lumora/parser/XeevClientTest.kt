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
        assertEquals("provider-123", parsed.channels.single().providerStreamId)
    }

    @Test fun skipsMalformedChannelRowsAndIgnoresUnknownRecordTypes() {
        val text = listOf(
            "#CAT\tcat-1\tNews",
            "#CHAN\tbad\tcat-1\tMissing URL\t0\tNEWS\t\t",
            "#MOVIE\tunknown layout",
            "#SERIES\tunknown layout"
        ).joinToString("\n")
        val failure = runCatching { client.parsePlaylist(text) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("no supported #CHAN records") == true)
    }
    @Test fun classifiesMoviesOnlyByUniqueVodIdJoinAndLeavesRemainderAsLive() {
        val text = listOf(
            "#CAT\tcat-live\tUK | TV",
            "#CAT\tcat-movie\tMovies",
            listOf("#CHAN", "live-1", "cat-live", "BBC One", "0", "BBC1", "", "https://stream.example/live").joinToString("\t"),
            listOf("#CHAN", "movie-1", "cat-movie", "Film title", "0", "", "", "https://stream.example/movie").joinToString("\t")
        ).joinToString("\n")
        val parsed = client.parsePlaylist(text)
        val split = parsed.classifyByVodRecordIds(setOf("movie-1"))

        assertEquals(listOf("live-1"), split.live.map { it.recordId })
        assertEquals(listOf("movie-1"), split.movies.map { it.recordId })
    }

    @Test fun refusesIncompleteOrAmbiguousVodIdJoin() {
        val uniqueText = listOf(
            "#CAT\tcat\tCategory",
            listOf("#CHAN", "record-1", "cat", "One", "0", "", "", "https://stream.example/1").joinToString("\t")
        ).joinToString("\n")
        val unique = client.parsePlaylist(uniqueText)
        assertTrue(runCatching { unique.classifyByVodRecordIds(setOf("missing")) }.isFailure)

        val duplicateText = listOf(
            "#CAT\tcat\tCategory",
            listOf("#CHAN", "duplicate", "cat", "One", "0", "", "", "https://stream.example/1").joinToString("\t"),
            listOf("#CHAN", "duplicate", "cat", "Two", "0", "", "", "https://stream.example/2").joinToString("\t")
        ).joinToString("\n")
        val duplicate = client.parsePlaylist(duplicateText)
        assertTrue(runCatching { duplicate.classifyByVodRecordIds(setOf("duplicate")) }.isFailure)
    }

    @Test fun keepsKeyDConfigurationRowsIncludingBlankStreamUrls() {
        val text = listOf(
            listOf("#CHAN", "key-t", "keyd-cat", "T", "0", "", "", "http://keyd.xyz/torbox-token").joinToString("\t"),
            listOf("#CHAN", "key-rd", "keyd-cat", "RD", "0", "", "", "").joinToString("\t"),
            "#CAT\tkeyd-cat\tKeyD",
            listOf("#CHAN", "live-1", "live-cat", "BBC One", "0", "BBC1", "", "https://stream.example/live").joinToString("\t"),
            "#CAT\tlive-cat\tUK | TV"
        ).joinToString("\n")

        val parsed = client.parsePlaylist(text)
        val keyd = parsed.channels.filter { parsed.categories.first { c -> c.id == it.categoryId }.name == "KeyD" }

        assertEquals(2, keyd.size)
        assertEquals("http://keyd.xyz/torbox-token", keyd.first { it.name == "T" }.playbackUrl)
        assertEquals("", keyd.first { it.name == "RD" }.playbackUrl)
        assertEquals(listOf("live-1"), parsed.channels.filterNot { it.categoryId == "keyd-cat" }.map { it.recordId })
    }

    @Test fun blankNonKeyDStreamUrlsAreStillRejected() {
        val text = listOf(
            "#CAT\tcat-1\tMovies",
            listOf("#CHAN", "bad", "cat-1", "No URL", "0", "", "", "").joinToString("\t")
        ).joinToString("\n")
        val failure = runCatching { client.parsePlaylist(text) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("no supported #CHAN records") == true)
    }

}
