package com.omni.downloader.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinUrlRoutingTest {
    private val workId = "7700835139168900858"

    @Test fun noteIsNotRewrittenAsVideo() = runBlocking {
        val resolved = UrlSniffer.sanitizeAndResolveUrl("https://m.douyin.com/share/note/$workId")
        assertEquals("https://www.douyin.com/note/$workId", resolved)
        assertTrue(DomesticDirectExtractor.isDouyinImagePostUrl(resolved))
    }

    @Test fun shareSlidesAreCanonicalizedAsNote() = runBlocking {
        val resolved = UrlSniffer.sanitizeAndResolveUrl("https://www.iesdouyin.com/share/slides/$workId/?is_slides=1")
        assertEquals("https://www.douyin.com/note/$workId", resolved)
        assertTrue(DomesticDirectExtractor.isDouyinImagePostUrl(resolved))
    }

    @Test fun normalVideoStillUsesVideoRoute() = runBlocking {
        val id = "7123456789012345678"
        val resolved = UrlSniffer.sanitizeAndResolveUrl("https://m.douyin.com/share/video/$id?share_app_id=1128")
        assertEquals("https://www.douyin.com/video/$id", resolved)
        assertFalse(DomesticDirectExtractor.isDouyinImagePostUrl(resolved))
    }
}
