package com.omni.downloader.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeUrlNormalizationTest {
    private val id = "gjUzH4UcSkw"
    private val canonical = "https://www.youtube.com/watch?v=$id"

    @Test fun mobilePpQueryAndDesktopWatchResolveIdentically() = runTest {
        val mobile = "https://m.youtube.com/watch?v=$id&pp=ugUHEgV6aC1DTg%3D%3D"
        val desktop = "https://youtube.com/watch?v=$id"
        assertEquals(canonical, UrlSniffer.sanitizeAndResolveUrl(mobile))
        assertEquals(canonical, UrlSniffer.sanitizeAndResolveUrl(desktop))
        assertEquals(canonical, UrlSniffer.sanitizeAndResolveUrl("https://www.youtube.com/watch?pp=abc&v=$id&t=72"))
        assertEquals("YouTube", UrlSniffer.identifySite(mobile))
        assertEquals(id, YouTubeNativeExtractor.videoId(mobile))
    }

    @Test fun shortLinksShortsLiveAndMusicShareSameCanonicalId() = runTest {
        val variants = listOf(
            "https://youtu.be/$id?si=tracking",
            "https://m.youtube.com/shorts/$id?feature=share",
            "https://youtube.com/live/$id?si=123",
            "https://music.youtube.com/watch?v=$id&list=OLAK5uy_abc",
            "https://www.youtube-nocookie.com/embed/$id?start=40",
            "http://www.youtube.com/watch?v=$id&t=123"
        )
        for (input in variants) {
            assertEquals("Failed to canonicalize $input", canonical, UrlSniffer.sanitizeAndResolveUrl(input))
        }
    }

    @Test fun youtubeTrackingQueryDoesNotTurnIntoFakeBilibiliId() = runTest {
        val input = "https://m.youtube.com/watch?v=$id&pp=av12345678"
        assertEquals(canonical, UrlSniffer.sanitizeAndResolveUrl(input))
        assertEquals("YouTube", UrlSniffer.identifySite(input))
    }

    @Test fun unrelatedLookalikeHostIsNotCanonicalized() = runTest {
        val spoof = "https://youtube.com.evil.example/watch?v=$id"
        assertNull(YouTubeNativeExtractor.canonicalWatchUrl(spoof))
        assertEquals(spoof, UrlSniffer.sanitizeAndResolveUrl(spoof))
    }

    @Test fun standaloneBilibiliIdsStillWork() = runTest {
        val idBv = "BV1xx411c7mD"
        assertEquals("https://www.bilibili.com/video/$idBv", UrlSniffer.sanitizeAndResolveUrl(idBv))
    }
}
