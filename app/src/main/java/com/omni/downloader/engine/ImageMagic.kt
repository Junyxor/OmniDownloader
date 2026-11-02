package com.omni.downloader.engine

import java.io.File
import java.io.FileInputStream

/** Detect an actual image payload, independent of URL extension and response headers. */
internal object ImageMagic {
    fun extension(file: File): String? {
        if (!file.isFile || file.length() < 12L) return null
        val bytes = ByteArray(32)
        val size = FileInputStream(file).use { it.read(bytes) }
        if (size < 12) return null
        val ascii = String(bytes, 0, size, Charsets.ISO_8859_1)
        return when {
            bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
                bytes[2] == 0xff.toByte() -> "jpg"
            bytes[0] == 0x89.toByte() && ascii.substring(1, 4) == "PNG" -> "png"
            ascii.startsWith("RIFF") && ascii.substring(8, 12) == "WEBP" -> "webp"
            ascii.startsWith("GIF8") -> "gif"
            ascii.substring(4, 8) == "ftyp" && ascii.substring(8, 12) in
                setOf("avif", "avis") -> "avif"
            ascii.substring(4, 8) == "ftyp" && (
                ascii.substring(8, 12).startsWith("hei") ||
                ascii.substring(8, 12) == "mif1"
            ) -> "heic"
            else -> null
        }
    }
}
