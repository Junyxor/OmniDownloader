package com.omni.downloader.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DouyinUrlNormalizationTest {
    @Test fun preservesNoteVsVideoInsteadOfRewritingBothToVideo() = runTest {
        assertEquals(
            "https://www.iesdouyin.com/share/slides/7700835139168900858",
            UrlSniffer.sanitizeAndResolveUrl("https://m.douyin.com/share/note/7700835139168900858")
        )
        assertEquals(
            "https://www.iesdouyin.com/share/video/7700835139168900858",
            UrlSniffer.sanitizeAndResolveUrl("https://m.douyin.com/share/video/7700835139168900858")
        )
        assertEquals("抖音", UrlSniffer.identifySite(
            "https://www.iesdouyin.com/share/slides/7700835139168900858"))
    }
}
