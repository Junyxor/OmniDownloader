package com.omni.downloader.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aria2FailureExplanationTest {
    @Test fun failedForbiddenRequestHasReadableCause() {
        val text = Aria2Downloader.explainFailure(22,
            "ERROR HTTP response: 403 https://cdn.googlevideo.com/videoplayback?token=secret")
        assertTrue(text.contains("403"))
        assertFalse(text.contains("secret"))
    }

    @Test fun networkAndCertificateFailuresDoNotExposeHeaders() {
        val cert = Aria2Downloader.explainFailure(1, "SSL certificate verification failed")
        assertTrue(cert.contains("TLS"))
        val conn = Aria2Downloader.explainFailure(1, "connection refused")
        assertTrue(conn.contains("网络"))
        assertFalse(conn.contains("Cookie="))
    }

    @Test fun rateLimitedAndExpiredUrlsAreDistinguished() {
        assertTrue(Aria2Downloader.explainFailure(22, "HTTP 429 Too Many Requests").contains("429"))
        assertTrue(Aria2Downloader.explainFailure(22, "HTTP 404 Not Found").contains("404"))
    }
}
