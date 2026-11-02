package com.omni.downloader.engine

import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.VideoMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MihoyoVideoExtractorTest {
    @Test fun recognizesWikiAndCommunityLinksWithoutLeakingIntoOtherHosts() {
        val wiki = "https://baike.mihoyo.com/bh3/wiki/content/936/detail?visit_device=mobile&bbs_presentation_style=no_header"
        val source = MihoyoVideoExtractor.parseSource(wiki)
        assertEquals(MihoyoVideoExtractor.SourceType.WIKI, source?.type)
        assertEquals("bh3_wiki", source?.appSn)
        assertEquals("936", source?.id)
        assertTrue(MihoyoVideoExtractor.isSupported("https://bbs.mihoyo.com/ys/obc/content/123/detail"))
        assertTrue(MihoyoVideoExtractor.isSupported("https://www.miyoushe.com/ys/article/79208044"))
        assertFalse(MihoyoVideoExtractor.isSupported("https://baike.mihoyo.com.evil.example/bh3/wiki/content/936/detail"))
        assertFalse(MihoyoVideoExtractor.isSupported("https://evil.example/?from=baike.mihoyo.com/bh3/wiki/content/936/detail"))
        assertFalse(MihoyoVideoExtractor.isSupported("https://baike.mihoyo.com/bh3/wiki/content/not-an-id/detail"))
    }

    @Test fun handlesIndexedVideosAndDifferentAppSnMappings() {
        val post = MihoyoVideoExtractor.parseSource(
            "https://www.miyoushe.com/ys/article/79208044#omni_vod=2"
        )
        assertEquals(MihoyoVideoExtractor.SourceType.POST, post?.type)
        assertEquals("79208044", post?.id)
        assertEquals(2, post?.videoIndex)
        assertEquals("sr_wiki", MihoyoVideoExtractor.parseSource(
            "https://bbs.mihoyo.com/sr/wiki/content/2915/detail"
        )?.appSn)
        assertEquals("ys_obc", MihoyoVideoExtractor.parseSource(
            "https://baike.mihoyo.com/ys/obc/content/129/detail"
        )?.appSn)
    }

    @Test fun parsesMobileHashRouteAndCanonicalizesDownloadTasks() {
        val mobile = MihoyoVideoExtractor.parseSource(
            "https://m.miyoushe.com/ys/#/article/79015625"
        )
        assertEquals(MihoyoVideoExtractor.SourceType.POST, mobile?.type)
        assertEquals("79015625", mobile?.id)
        assertEquals("ys", mobile?.postGame)
        assertEquals(
            "https://www.miyoushe.com/ys/article/79015625",
            MihoyoVideoExtractor.canonicalPostUrl(mobile!!)
        )
        assertEquals("米游社", UrlSniffer.identifySite("https://m.miyoushe.com/ys/#/article/79015625"))
        val indexed = MihoyoVideoExtractor.parseSource(
            "https://m.miyoushe.com/ys/#/article/79015625?omni_vod=2"
        )
        assertEquals(2, indexed?.videoIndex)
        assertEquals(
            "https://www.miyoushe.com/ys/article/79015625#omni_vod=2",
            MihoyoVideoExtractor.canonicalPostUrl(indexed!!)
        )
        val standard = MihoyoVideoExtractor.parseSource(
            "https://www.miyoushe.com/ys/article/79015625#omni_vod=2"
        )
        assertEquals("79015625", standard?.id)
        assertEquals(2, standard?.videoIndex)
        assertEquals(null, MihoyoVideoExtractor.parseSource(
            "https://m.miyoushe.com.evil.example/ys/#/article/79015625"
        ))
        assertEquals(null, MihoyoVideoExtractor.parseSource(
            "https://m.miyoushe.com/ys/#/other/79015625"
        ))
    }

    @Test fun extractsVideoAndSourceTagsButNotUntrustedHosts() {
        val html = """
            <div>
                <video title="test" src="https://uploadstatic.mihoyo.com/bh3-wiki/one.mp4?x=1&amp;y=2"
                       poster="https://uploadstatic.mihoyo.com/cover.jpg"></video>
                <video controls><source src='https://prod-vod-sign.miyoushe.com/second.mp4'></video>
                <video src="https://uploadstatic.mihoyo.com/bh3-wiki/one.mp4?x=1&amp;y=2"></video>
                <video src="https://mihoYo.com.evil.org/evil.mp4"></video>
                <video src="http://uploadstatic.mihoyo.com/insecure.mp4"></video>
            </div>
        """.trimIndent()
        val videos = MihoyoVideoExtractor.extractHtmlVideos(html)
        assertEquals(2, videos.size)
        assertEquals("https://uploadstatic.mihoyo.com/bh3-wiki/one.mp4?x=1&y=2", videos[0].url)
        assertEquals("https://uploadstatic.mihoyo.com/cover.jpg", videos[0].poster)
        assertEquals("https://prod-vod-sign.miyoushe.com/second.mp4", videos[1].url)
    }

    @Test fun detectsExpiringSignedUrlsAndDoesNotPersistCommunityVideos() {
        val now = 1793600000L
        assertTrue(MihoyoVideoExtractor.isExpiring("https://prod-vod-sign.miyoushe.com/x?auth_key=1793600120-abc", now))
        assertFalse(MihoyoVideoExtractor.isExpiring("https://prod-vod-sign.miyoushe.com/x?auth_key=1793601500-abc", now))
        assertFalse(MihoyoVideoExtractor.isExpiring("https://uploadstatic.mihoyo.com/file.mp4", now))
        val meta = VideoMetadata(
            url = "https://www.miyoushe.com/ys/article/12345",
            title = "post",
            siteName = "米游社",
            availableVideoFormats = listOf(FormatOption(
                formatId = "https://prod-vod-sign.miyoushe.com/x?auth_key=1-test",
                resolutionLabel = "1080P"
            ))
        )
        assertEquals("", DomesticMediaSource.url(meta))
        assertEquals("https://www.miyoushe.com/", DomesticMediaSource.headers("米游社")["Referer"])
    }
}
