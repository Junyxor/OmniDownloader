package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test

class YouTubeNativeExtractorTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun recognizesWatchShortsAndShortLinksWithoutSpoofedHosts() {
        assertEquals(id, YouTubeNativeExtractor.videoId("https://www.youtube.com/watch?v=$id&t=20"))
        assertEquals(id, YouTubeNativeExtractor.videoId("https://youtu.be/$id"))
        assertEquals(id, YouTubeNativeExtractor.videoId("https://m.youtube.com/shorts/$id"))
        assertEquals(id, YouTubeNativeExtractor.videoId("https://youtube.com/live/$id"))
        assertNull(YouTubeNativeExtractor.videoId("https://youtube.com.evil.test/watch?v=$id"))
        assertNull(YouTubeNativeExtractor.videoId("file:///watch?v=$id"))
    }

    @Test fun balancedJsonDoesNotTruncateNestedAndQuotedBraces() {
        val page = """<script>var ytInitialPlayerResponse = {"videoDetails":{"title":"A } quote","nested":{"x":1}},"streamingData":{"formats":[]}};</script>"""
        val json = YouTubeNativeExtractor.jsonAfter(page, "ytInitialPlayerResponse")!!
        assertTrue(json.contains("streamingData"))
        assertTrue(json.endsWith("}"))
    }

    @Test fun onlyMuxedProgressiveStreamsAndDirectM4aAreAdvertised() {
        val json = """
        {"playabilityStatus":{"status":"OK"},
         "videoDetails":{"videoId":"$id","title":"A test video","author":"owner",
                         "lengthSeconds":"123"},
         "streamingData":{"formats":[
           {"itag":18,"mimeType":"video/mp4",
            "url":"https://r1.googlevideo.com/videoplayback?x=18","height":360,
            "qualityLabel":"360p","audioQuality":"AUDIO_QUALITY_LOW"},
           {"itag":137,"mimeType":"video/mp4",
            "url":"https://r1.googlevideo.com/video_only","height":1080,
            "qualityLabel":"1080p"}],
          "adaptiveFormats":[
           {"itag":140,"mimeType":"audio/mp4",
            "url":"https://r1.googlevideo.com/audio","bitrate":128000}]}}
        """.trimIndent()
        val result = YouTubeNativeExtractor.parsePlayer(json, id).getOrThrow()
        assertEquals("youtube-native", result.parserId)
        assertEquals("A test video", result.title)
        assertEquals("2:03", result.durationText)
        assertEquals(1, result.availableVideoFormats.size)
        assertEquals("360p", result.availableVideoFormats.first().resolutionLabel)
        assertEquals(1, result.availableAudioFormats.size)
        assertEquals("m4a", result.availableAudioFormats.first().ext)
    }

    @Test fun rejectsMismatchedIdAndEncryptedOnlyStreams() {
        val data = """{"videoDetails":{"videoId":"$id"},
                    "streamingData":{"formats":[
                    {"mimeType":"video/mp4","signatureCipher":"s=encrypted"}]}}"""
        val failed = YouTubeNativeExtractor.parsePlayer(data, id)
        assertTrue(failed.isFailure)
        assertTrue(failed.exceptionOrNull()?.message.orEmpty().contains("解签名"))
        assertTrue(YouTubeNativeExtractor.parsePlayer(data.replace(id, "aaaaaaaaaaa"), id).isFailure)
    }

    @Test fun rejectsPlaybackRestrictionsAndLiveVideo() {
        val blocked = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"},
                         "videoDetails":{"videoId":"$id"}}"""
        assertTrue(YouTubeNativeExtractor.parsePlayer(blocked, id).isFailure)
        val live = """{"videoDetails":{"videoId":"$id","isLiveContent":true},
                      "streamingData":{"formats":[]}}"""
        assertTrue(YouTubeNativeExtractor.parsePlayer(live, id).isFailure)
    }
}
