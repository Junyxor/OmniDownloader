package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test

class DouyinStructuredParserTest {
    private val id = "7700835139168900858"
    private val url = "https://www.iesdouyin.com/share/slides/" + id

    @Test fun nestedMobileImagePostRequiresExactAwemeId() {
        val json = """{"loaderData":{"videoInfoRes":{"item_list":[
           {"aweme_id":"9999999999999999999","desc":"Wrong",
            "video":{"play_addr":{"url_list":["https://cdn.test/wrong.mp4"]}}},
           {"aweme_id":"7700835139168900858","aweme_type":68,"desc":"Image post",
            "author":{"nickname":"Artist"},
            "images":[{"url_list":["https://img.test/1.jpg"]},
                      {"display_image":{"url_list":["https://img.test/2.jpg"]}}]}
        ]}}}"""
        val result = DouyinStructuredParser.parse(json, id, url)
        assertNotNull(result)
        assertEquals("图文", result!!.siteName)
        assertEquals("douyin", result.parserId)
        assertEquals("Artist", result.author)
        assertEquals(2, result.multiMediaList.size)
        assertTrue(result.multiMediaList[0].url.endsWith("#omni_douyin_image=0"))
        assertNull(DouyinStructuredParser.parse(json, "8000000000000000000", url))
    }

    @Test fun balancedRouterDataIsNotTruncatedAtNestedBrace() {
        val html = """<script>window._ROUTER_DATA = {"nested":{"videoInfoRes":{
          "item_list":[{"aweme_id":"7700835139168900858","desc":"Video",
          "video":{"play_addr":{"url_list":["https://cdn.test/real.mp4"]}}}]}}};</script>"""
        val result = DouyinStructuredParser.parseHtml(html, id,
            "https://www.iesdouyin.com/share/video/" + id)
        assertEquals("Video", result?.title)
        assertEquals("https://cdn.test/real.mp4",
            result?.availableVideoFormats?.firstOrNull()?.formatId)
    }

    @Test fun rejectedUnrelatedSuggestions() {
        val json = """{"aweme_list":[{"aweme_id":"9999999999999999999",
           "video":{"play_addr":{"url_list":["https://cdn.test/other.mp4"]}}}]}"""
        assertNull(DouyinStructuredParser.parse(json, id, url))
    }
    @Test fun galleryKeepsDuplicatePhotosAndMirrorUrls() {
        val json = """{"aweme_detail":{"aweme_id":"$id","aweme_type":68,"desc":"Duplicated",
          "images":[
            {"width":1080,"height":1920,
             "url_list":["https://img.test/repeated.jpg","https://mirror.test/repeated.jpg"],
             "video":{"play_addr":{"url_list":["https://img.test/live.mp4"]}}},
            {"width":720,"height":1280,"url_list":["https://img.test/repeated.jpg"]}
          ]}}}"""
        val result = DouyinStructuredParser.parse(json, id, url)!!
        assertEquals(2, result.multiMediaList.size)
        assertEquals(listOf(0, 1), result.imageAssets.map { it.index })
        assertEquals("https://img.test/repeated.jpg", result.imageAssets[0].url)
        assertEquals("https://img.test/repeated.jpg", result.imageAssets[1].url)
        assertEquals(listOf("https://mirror.test/repeated.jpg"), result.imageAssets[0].mirrors)
        assertEquals("https://img.test/live.mp4", result.imageAssets[0].liveVideoUrl)
        assertEquals(1080, result.imageAssets[0].width)
    }

    @Test fun corruptedGalleryEntryDoesNotSilentlyDropAnImage() {
        val json = """{"aweme_detail":{"aweme_id":"$id","aweme_type":68,
            "images":[{"url_list":["https://img.test/1.jpg"]},{"url_list":[]}]}}"""
        assertNull(DouyinStructuredParser.parse(json, id, url))
    }

    @Test fun videosExposeResolutionsAndNeverClaimStringReplacedWatermarkRemoval() {
        val json = """{"aweme_detail":{"aweme_id":"$id","desc":"Video",
          "video":{"bit_rate":[
            {"play_addr":{"width":720,"height":1280,"url_list":["https://cdn.test/720.mp4"]}},
            {"play_addr":{"width":1080,"height":1920,"url_list":["https://cdn.test/playwm-1080.mp4"]}}
          ]}}}"""
        val result = DouyinStructuredParser.parse(json, id,
            "https://www.iesdouyin.com/share/video/$id")!!
        assertEquals(2, result.availableVideoFormats.size)
        assertEquals(1920, result.availableVideoFormats.first().height)
        assertEquals("https://cdn.test/playwm-1080.mp4", result.availableVideoFormats.first().formatId)
        assertTrue(result.availableVideoFormats.first().note.contains("未验证"))
    }

    @Test fun originalImageUrlHasPriorityOverDisplayThumbnail() {
        val json = """{"aweme_detail":{"aweme_id":"$id","desc":"Original",
           "images":[{"url_list":["https://cdn.test/original.jpg"],
           "display_image":{"url_list":["https://cdn.test/preview.jpg"]}}]}}"""
        val meta = DouyinStructuredParser.parse(json, id,
            "https://www.iesdouyin.com/share/video/$id")!!
        assertEquals("图文", meta.siteName)
        assertEquals("https://cdn.test/original.jpg", meta.imageAssets.first().url)
        assertEquals("https://cdn.test/preview.jpg", meta.imageAssets.first().mirrors.single())
    }

    @Test fun incompleteSlideDetailMustNotBeParsedAsVideo() {
        val json = """{"aweme_detail":{"aweme_id":"$id","aweme_type":68,
           "video":{"play_addr":{"url_list":["https://cdn.test/not-the-album.mp4"]}}}}"""
        assertNull(DouyinStructuredParser.parse(json, id, url))
    }

}
