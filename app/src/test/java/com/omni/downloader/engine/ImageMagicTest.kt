package com.omni.downloader.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ImageMagicTest {
    private fun withBytes(bytes: ByteArray, block: (File) -> Unit) {
        val f = File.createTempFile("image-magic-", ".bin")
        try {
            f.writeBytes(bytes)
            block(f)
        } finally {
            f.delete()
        }
    }

    @Test fun recognizesJpegPngWebpAndAvif() {
        withBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()) + ByteArray(32)) {
            assertEquals("jpg", ImageMagic.extension(it))
        }
        withBytes(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10) + ByteArray(24)) {
            assertEquals("png", ImageMagic.extension(it))
        }
        withBytes("RIFF____WEBP".toByteArray() + ByteArray(24)) {
            assertEquals("webp", ImageMagic.extension(it))
        }
        withBytes(byteArrayOf(0,0,0,24) + "ftypavif".toByteArray() + ByteArray(24)) {
            assertEquals("avif", ImageMagic.extension(it))
        }
    }

    @Test fun rejectsErrorPagesAndPlaylistFiles() {
        withBytes("<!doctype html><html><body>Blocked</body></html>".toByteArray()) {
            assertNull(ImageMagic.extension(it))
        }
        withBytes("{\"message\":\"rate limit\"}".toByteArray()) {
            assertNull(ImageMagic.extension(it))
        }
        withBytes("#EXTM3U\n#EXTINF:1\nsegment.ts".toByteArray()) {
            assertNull(ImageMagic.extension(it))
        }
    }
}
