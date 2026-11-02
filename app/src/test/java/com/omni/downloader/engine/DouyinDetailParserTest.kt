package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test

class DouyinDetailParserTest {
    private val id = "7700835139168900858"
    private val noteUrl = "https://www.douyin.com/note/$id"

    private fun albumResponse() = """
      {
        "status_code": 0,
        "aweme_detail": {
          "aweme_id": "$id",
          "desc": "gallery",
          "author": {"nickname": "creator"},
          "images": [
            {
              "width": 1080, "height": 1920,
              "url_list": ["https://example.org/1.jpg", "https://mirror.example.org/1.jpg"],
              "video": {"play_addr": {"url_list": ["https://example.org/live1.mp4"]}}
            },
            {"width": 1440, "height": 2560, "url_list": ["https://example.org/2.jpg"]}
          ]
        }
      }
    """.trimIndent()

    @Test fun galleryKeepsImageOrderMirrorsAndLivePhoto() {
        val result = DouyinDetailParser.parse(albumResponse(), noteUrl, id).getOrThrow()
        assertEquals(noteUrl, result.url)
        assertEquals("抖音", result.siteName)
        assertEquals("gallery", result.title)
        assertEquals(2, result.imageAssets.size)
        assertEquals(listOf(0, 1), result.imageAssets.map { it.index })
        assertEquals(listOf("https://example.org/1.jpg", "https://example.org/2.jpg"),
            result.imageAssets.map { it.url })
        assertEquals(listOf("https://mirror.example.org/1.jpg"), result.imageAssets[0].mirrors)
        assertEquals("https://example.org/live1.mp4", result.imageAssets[0].liveVideoUrl)
        assertEquals(1440, result.imageAssets[1].width)
        assertEquals("https://example.org/1.jpg", result.thumbnailUrl)
        assertTrue(result.availableVideoFormats.isEmpty())
    }

    @Test fun rejectsMissingMediaUrlInsteadOfCreatingFakeSuccess() {
        val invalid = albumResponse().replace(
            "\"url_list\": [\"https://example.org/2.jpg\"]",
            "\"url_list\": []"
        )
        assertTrue(DouyinDetailParser.parse(invalid, noteUrl, id).isFailure)
    }

    @Test fun refusesMismatchedWorkId() {
        val parsed = DouyinDetailParser.parse(albumResponse(), noteUrl, "7111111111111111111")
        assertTrue(parsed.isFailure)
    }

    @Test fun refusesMismatchedFeedEntry() {
        val feed = """{"aweme_list":[{"aweme_id":"7111111111111111111","video":{"play_addr":{"url_list":["https://example.org/other.mp4"]}}}]}"""
        assertTrue(DouyinDetailParser.parse(feed, noteUrl, id).isFailure)
    }

    @Test fun rejectsEmptyOrBlockedDetail() {
        assertTrue(DouyinDetailParser.parse("""{"status_code":0,"aweme_detail":null}""", noteUrl, id).isFailure)
        assertTrue(DouyinDetailParser.parse("""{"status_code":403}""", noteUrl, id).isFailure)
    }

    @Test fun rejectsHttpErrorPageInImageCandidates() {
        val response = albumResponse().replace("https://example.org/2.jpg", "javascript:alert(1)")
        assertTrue(DouyinDetailParser.parse(response, noteUrl, id).isFailure)
    }

    @Test fun videoFormatsPreferHigherResolutionWithoutStringNoWatermarkHack() {
        val response = """
          {"aweme_detail":{"aweme_id":"$id","desc":"video",
          "video":{"bit_rate":[
            {"play_addr":{"width":720,"height":1280,"url_list":["https://example.org/720.mp4"]}},
            {"play_addr":{"width":1080,"height":1920,"url_list":["https://example.org/playwm-1080.mp4"]}}
          ]}}}
        """.trimIndent()
        val result = DouyinDetailParser.parse(response, "https://www.douyin.com/video/$id", id).getOrThrow()
        assertTrue(result.imageAssets.isEmpty())
        assertEquals(1920, result.availableVideoFormats.first().height)
        assertEquals("https://example.org/playwm-1080.mp4", result.availableVideoFormats.first().formatId)
    }
}
