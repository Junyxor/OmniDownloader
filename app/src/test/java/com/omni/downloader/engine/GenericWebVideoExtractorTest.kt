package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test

class GenericWebVideoExtractorTest {
    @Test fun discoversHtml5VideoAndOpenGraphWithoutGuessingUnrelatedLinks() {
        val html = """
          <html><head><title>Demo clip</title>
          <meta property="og:video" content="https://cdn.example.org/real.mp4">
          <meta property="og:image" content="https://cdn.example.org/cover.jpg">
          </head><body>
          <video controls><source src="/videos/second.mp4" type="video/mp4"></video>
          <a href="https://cdn.example.org/recommendation.mp4">Recommended</a>
          </body></html>
        """.trimIndent()
        val metadata = GenericWebVideoExtractor.parseHtml(html, "https://video.example.org/watch/123")
        assertNotNull(metadata)
        assertEquals("Demo clip", metadata!!.title)
        assertEquals(2, metadata.multiMediaList.size)
        assertEquals("https://cdn.example.org/real.mp4",
            metadata.multiMediaList[0].availableVideoFormats[0].formatId)
        assertEquals("https://video.example.org/videos/second.mp4",
            metadata.multiMediaList[1].availableVideoFormats[0].formatId)
        assertEquals("https://cdn.example.org/cover.jpg", metadata.thumbnailUrl)
    }

    @Test fun acceptsOnlyHttpsMediaAndRejectsUnsupportedPlaylists() {
        val html = """
          <video src="http://insecure.example.org/a.mp4"></video>
          <source src="https://cdn.example.org/live.m3u8" />
          <meta property="og:video" content="https://localhost/secret.mp4"/>
          <video src="https://cdn.example.org/safe.mp4"></video>
        """.trimIndent()
        val candidates = GenericWebVideoExtractor.candidates(html, "https://video.example.org/watch")
        assertEquals(1, candidates.size)
        assertEquals("https://cdn.example.org/safe.mp4", candidates.single().url)
    }

    @Test fun supportsJsonLdVideoObjectWithoutBlindScrapingScriptStrings() {
        val html = """
          <script type="application/ld+json">
          {"@context":"https://schema.org","@type":"VideoObject",
          "name":"Film","contentUrl":"https://media.example.org/film.mp4",
          "other":{"preview":"https://media.example.org/unrelated.mp4"}}
          </script>
        """.trimIndent()
        val found = GenericWebVideoExtractor.candidates(html, "https://video.example.org/watch")
        assertEquals(listOf("https://media.example.org/film.mp4"), found.map { it.url })
    }

    @Test fun rejectsPagesWithoutExplicitMedia() {
        assertNull(GenericWebVideoExtractor.parseHtml(
            "<html><body><a href='https://cdn.example.org/file.mp4'>Related</a></body></html>",
            "https://example.org/page"))
    }
}
