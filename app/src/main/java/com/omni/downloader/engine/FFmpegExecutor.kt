package com.omni.downloader.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Zero-bundled-codec Android MP4 remuxing. Does not transcode or rename formats.
 * MediaMuxer handles H264/HEVC/AAC where the platform MediaExtractor supports the stream.
 */
object FFmpegExecutor {
    private fun copyTracks(sources: List<Pair<File, Boolean>>, outputFile: File): Result<Unit> {
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        return try {
            outputFile.parentFile?.mkdirs()
            val selected = mutableListOf<Triple<MediaExtractor, Int, MediaFormat>>()
            for ((file, audioOnly) in sources) {
                if (!file.exists() || file.length() == 0L) throw IOException("音视频轨源文件为空")
                val ex = MediaExtractor()
                extractors.add(ex)
                ex.setDataSource(file.absolutePath)
                for (i in 0 until ex.trackCount) {
                    val format = ex.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    val accept = if (audioOnly) mime.startsWith("audio/") else mime.startsWith("video/")
                    if (accept) {
                        selected += Triple(ex, i, format)
                        break
                    }
                }
            }
            if (selected.isEmpty()) throw IOException("系统媒体解封装器无法识别轨道")
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val mappings = selected.map { (ex, index, format) ->
                val dst = muxer!!.addTrack(format)
                Triple(ex, index, dst)
            }
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for ((extractor, track, muxTrack) in mappings) {
                extractor.selectTrack(track)
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    if (size > buffer.capacity()) throw IOException("媒体样本超过缓冲区")
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0)
                    info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(muxTrack, buffer, info)
                    extractor.advance()
                }
                extractor.unselectTrack(track)
            }
            muxer.stop()
            muxer.release()
            muxer = null
            if (outputFile.length() < 1024L) throw IOException("MP4 混流输出为空")
            Result.success(Unit)
        } catch (e: Exception) {
            outputFile.delete()
            Result.failure(e)
        } finally {
            extractors.forEach { runCatching { it.release() } }
            runCatching { muxer?.release() }
        }
    }

    suspend fun mergeVideoAndAudio(context: Context, videoFile: File, audioFile: File, outputFile: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            copyTracks(listOf(videoFile to false, audioFile to true), outputFile)
        }

    suspend fun stripAudio(context: Context, videoFile: File, outputFile: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            copyTracks(listOf(videoFile to false), outputFile)
        }

    suspend fun extractAudio(
        context: Context, rawAudioFile: File, outputFile: File,
        format: String, forceEncode: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (format.lowercase() !in setOf("m4a", "mp4")) {
            return@withContext Result.failure(IOException(
                "精简版仅支持原音轨无损提取 M4A；MP3/FLAC/OPUS 需要另装转码模块"
            ))
        }
        copyTracks(listOf(rawAudioFile to true), outputFile)
    }

    suspend fun convertToGif(context: Context, inputFile: File, outputFile: File): Result<Unit> =
        Result.failure(IOException("精简版暂不支持视频转 GIF，请选 MP4 下载"))

    fun getFFmpegBinary(context: Context): File? = null
}
