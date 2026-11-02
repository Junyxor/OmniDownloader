package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test

class XPublicExtractorTest {
    @Test fun parsesPublicXVideoQualitiesByExactPostId() {
        val sample = """{"code":200,"status":{"type":"status","id":"1548119328498728960",
          "text":"Video test","author":{"name":"Author"},
          "media":{"videos":[{"type":"video","url":"https://video.twimg.com/a.mp4",
          "thumbnail_url":"https://pbs.twimg.com/preview.jpg","formats":[
          {"container":"m3u8","url":"https://video.twimg.com/a.m3u8","height":1080},
          {"container":"mp4","url":"https://video.twimg.com/720.mp4","height":720},
          {"container":"mp4","url":"https://video.twimg.com/360.mp4","height":360}
          ]}]}}}"""
        val data = XPublicExtractor.parse(sample,
            "https://x.com/example/status/1548119328498728960")
        assertEquals("X (Twitter)", data.siteName)
        assertEquals("fx", data.parserId)
        assertEquals("Video test", data.title)
        assertEquals(2, data.availableVideoFormats.size)
        assertEquals("720p MP4", data.availableVideoFormats[0].resolutionLabel)
        assertEquals("https://video.twimg.com/720.mp4",
            data.availableVideoFormats[0].formatId)
    }

    @Test fun rejectsWrongPostAndPreservesPhotoGallery() {
        val sample = """{"code":200,"status":{"type":"status","id":"1548119328498728960",
          "text":"Gallery","media":{"photos":[
          {"type":"photo","url":"https://pbs.twimg.com/a.jpg"},
          {"type":"photo","url":"https://pbs.twimg.com/b.jpg"}]}}}"""
        val requested = "https://twitter.com/u/status/1548119328498728960"
        val result = XPublicExtractor.parse(sample, requested)
        assertEquals(2, result.multiMediaList.size)
        assertEquals("图文", result.siteName)
        assertEquals("fx", result.multiMediaList[0].parserId)
        assertTrue(result.multiMediaList[1].url.endsWith("#omni_fx_image=1"))
        try {
            XPublicExtractor.parse(sample, "https://x.com/u/status/1548119328498728999")
            fail("An unrelated post must not be accepted")
        } catch (expected: java.io.IOException) { }
    }
}
