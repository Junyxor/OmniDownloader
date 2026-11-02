package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test

class LuxExtractorTest {
    @Test fun youtubeStreamsPreserveQualityAndExtractorIdentity() {
        val sample = """[{"site":"YouTube youtube.com","title":"Sample",
          "type":"video","streams":{
            "18":{"quality":"360P mp4","parts":[{"url":"https://cdn.test/18.mp4","ext":"mp4"}]},
            "137":{"quality":"1080P mp4","parts":[
              {"url":"https://cdn.test/v.mp4","ext":"mp4"},
              {"url":"https://cdn.test/a.m4a","ext":"m4a"}]}
          }}]"""
        val data = LuxExtractor.metadata(sample, "https://www.youtube.com/watch?v=abcdefghijk")
        assertEquals("YouTube", data.siteName)
        assertEquals("lux", data.parserId)
        assertEquals(2, data.availableVideoFormats.size)
        assertTrue(data.availableVideoFormats.any { it.resolutionLabel.contains("1080P") })
        assertTrue(data.availableVideoFormats.all { it.formatId.startsWith("lux:") })
    }

    @Test fun imageGalleryCreatesDistinctDownloadableEntries() {
        val sample = """[{"site":"抖音 douyin.com","title":"Pictures","type":"image",
          "streams":{"default":{"parts":[
            {"url":"https://pic.test/1.jpeg","ext":"jpeg"},
            {"url":"https://pic.test/2.jpeg","ext":"jpeg"}]}}}]"""
        val url = "https://www.iesdouyin.com/share/slides/7700835139168900858"
        val data = LuxExtractor.metadata(sample, url)
        assertEquals("图文", data.siteName)
        assertEquals(2, data.multiMediaList.size)
        assertEquals("lux", data.multiMediaList.first().parserId)
        assertEquals(url + "#omni_lux_image=1", data.multiMediaList[1].url)
        assertEquals("https://pic.test/2.jpeg", data.multiMediaList[1].thumbnailUrl)
    }
}
