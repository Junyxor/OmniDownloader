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
}
