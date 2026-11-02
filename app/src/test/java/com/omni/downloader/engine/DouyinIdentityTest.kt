package com.omni.downloader.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DouyinIdentityTest {
    @Test fun onlyExtractsExplicitWorkIdsFromRoutesAndQueries() {
        assertEquals("7901562512345678901",
            DomesticDirectExtractor.douyinIdFromUrl(
                "https://www.iesdouyin.com/share/slide/7901562512345678901"))
        assertEquals("7369880000000000000",
            DomesticDirectExtractor.douyinIdFromUrl(
                "https://www.douyin.com/video/7369880000000000000"))
        assertEquals("7369880000000000000",
            DomesticDirectExtractor.douyinIdFromUrl(
                "https://www.douyin.com/?aweme_id=7369880000000000000"))
        assertEquals("", DomesticDirectExtractor.douyinIdFromUrl(
            "https://www.douyin.com/share?analytics_id=7369880000000000000"))
    }
}
