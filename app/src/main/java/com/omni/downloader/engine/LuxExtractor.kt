package com.omni.downloader.engine

import android.content.Context
import android.media.MediaScannerConnection
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Pinned MIT Lux extractor: https://github.com/iawia002/lux
 * Local Go/Android PIE binary extracts media URLs. aria2 transfers the media.
 */
object LuxExtractor {
    private val imageMarker = Regex("""(?:^|[?&])omni_lux_image=(\d+)""")
    data class Part(val url: String, val ext: String)
    data class Stream(val id: String, val label: String, val parts: List<Part>)
    fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, "liblux.so")
    fun isAvailable(context: Context) = binary(context).let { it.isFile && it.canExecute() }
    fun supports(site: String): Boolean = site in setOf(
        "YouTube", "X (Twitter)", "Instagram", "Facebook", "Pinterest",
        "TikTok", "抖音", "快手", "小红书", "网络视频"
    )

    private fun siteName(raw: String): String {
        val s = raw.lowercase()
        return when {
            "youtube" in s -> "YouTube"
            "instagram" in s -> "Instagram"
            "twitter" in s -> "X (Twitter)"
            "facebook" in s -> "Facebook"
            "pinterest" in s -> "Pinterest"
            "tiktok" in s -> "TikTok"
            "douyin" in s || "抖音" in s -> "抖音"
            "kuaishou" in s || "快手" in s -> "快手"
            "xiaohongshu" in s || "小红书" in s -> "小红书"
            else -> raw.substringBefore(' ').ifBlank { "网页视频" }
        }
    }

    internal fun parseJson(raw: String): JSONArray {
        val start = raw.indexOf('[')
        if (start < 0) throw IOException("Lux 没有返回视频 JSON")
        val data = JSONArray(raw.substring(start))
        if (data.length() == 0) throw IOException("没有发现媒体")
        return data
    }

    private fun item(json: JSONArray): JSONObject =
        (0 until json.length()).mapNotNull { json.optJSONObject(it) }
            .firstOrNull { it.optJSONObject("streams") != null }
            ?: throw IOException("Lux 没有提取到媒体流")

    internal fun streams(info: JSONObject): List<Stream> {
        val json = info.optJSONObject("streams") ?: return emptyList()
        val out = mutableListOf<Stream>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val obj = json.optJSONObject(id) ?: continue
            val parts = obj.optJSONArray("parts") ?: obj.optJSONArray("urls") ?: continue
            val list = (0 until parts.length()).mapNotNull { k ->
                val v = parts.optJSONObject(k) ?: return@mapNotNull null
                val url = v.optString("url")
                if (!url.startsWith("http://") && !url.startsWith("https://")) return@mapNotNull null
                Part(url, v.optString("ext"))
            }
            if (list.isNotEmpty()) {
                val quality = obj.optString("quality").ifBlank { id }
                out += Stream(id, "$quality [$id]", list)
            }
        }
        return out.sortedByDescending {
            Regex("""(\d{3,4})[pP]""").find(it.label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
    }

    private fun photos(info: JSONObject) = streams(info).firstOrNull()?.parts.orEmpty()

    internal fun metadata(raw: String, requestUrl: String): VideoMetadata {
        val info = item(parseJson(raw))
        val title = info.optString("title").ifBlank { "网页媒体" }
        val base = requestUrl.substringBefore('#')
        if (info.optString("type") == "image") {
            val images = photos(info)
            if (images.isEmpty()) throw IOException("图文没有图片 URL")
            val children = images.mapIndexed { i, img ->
                VideoMetadata(
                    url = "$base#omni_lux_image=$i",
                    title = "$title · 图片 " + (i + 1),
                    siteName = "图文",
                    thumbnailUrl = img.url,
                    parserId = "lux"
                )
            }
            return VideoMetadata(
                url = base, title = title, siteName = "图文",
                thumbnailUrl = images.first().url, multiMediaList = children, parserId = "lux"
            )
        }
        // Do not advertise qualities that our lightweight Android muxer cannot
        // actually save. A single WebM file is fine; WebM DASH dual-track is not.
        val formats = streams(info).filter { stream ->
            stream.parts.size == 1 ||
                (stream.parts.size == 2 &&
                    stream.parts.all { it.ext.lowercase() in setOf("mp4", "m4a") })
        }.map { stream ->
            FormatOption(
                formatId = "lux:" + stream.id,
                resolutionLabel = stream.label,
                ext = stream.parts.first().ext.ifBlank { "mp4" },
                note = if (stream.parts.size == 2) "高清分轨（aria2 + 原生合并）"
                    else "本地原生解析 · aria2 下载"
            )
        }
        if (formats.isEmpty()) throw IOException("没有找到可用的视频格式")
        return VideoMetadata(
            url = base, title = title, siteName = siteName(info.optString("site")),
            availableVideoFormats = formats, parserId = "lux"
        )
    }

    private fun execute(context: Context, url: String, proxy: String): String {
        if (!isAvailable(context)) throw IOException("缺少 Lux 原生解析模块，请安装完整版 APK")
        val pb = ProcessBuilder(binary(context).absolutePath, "-j", url.substringBefore('#'))
        if (proxy.isNotBlank()) {
            pb.environment()["HTTP_PROXY"] = proxy
            pb.environment()["HTTPS_PROXY"] = proxy
            pb.environment()["ALL_PROXY"] = proxy
        }
        val process = pb.start()
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        fun drain(stream: java.io.InputStream, to: ByteArrayOutputStream, limit: Int): Thread {
            return Thread {
                runCatching {
                    stream.use { input ->
                        val bytes = ByteArray(16384)
                        while (true) {
                            val n = input.read(bytes)
                            if (n < 0) break
                            if (to.size() + n > limit) {
                                process.destroyForcibly()
                                break
                            }
                            to.write(bytes, 0, n)
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        val out = drain(process.inputStream, output, 8 * 1024 * 1024)
        val err = drain(process.errorStream, errors, 2048)
        try {
            if (!process.waitFor(55, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IOException("视频解析超时，请检查网络或平台登录")
            }
            out.join(1500); err.join(1500)
            val json = output.toString("UTF-8")
            if (process.exitValue() != 0 || json.isBlank()) {
                val diagnostic = errors.toString("UTF-8")
                    .replace(Regex("""https?://[^\s]+"""), "[URL]").takeLast(200)
                throw IOException("Lux 解析失败 (exit " + process.exitValue() + "): " + diagnostic)
            }
            return json
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    suspend fun extract(context: Context, url: String, proxy: String = ""): Result<VideoMetadata> =
        withContext(Dispatchers.IO) {
            runCatching { metadata(execute(context, url, proxy), url) }
        }

    suspend fun download(
        context: Context, task: DownloadTask, staging: File, outputDir: File,
        progress: (Float, String, String, TaskStatus) -> Unit, proxy: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = task.url.substringBefore('#')
            val info = item(parseJson(execute(context, base, proxy)))
            val image = imageMarker.find(task.url.substringAfter('#', ""))?.groupValues
                ?.get(1)?.toIntOrNull()
            if (image != null) {
                if (info.optString("type") != "image")
                    throw IOException("原作品已经不是图文类型")
                val link = photos(info).getOrNull(image)?.url
                    ?: throw IOException("选中的图片已失效")
                return@runCatching DownloadEngine.downloadCoverFile(
                    context, link, task.title, proxy, outputDir, progress
                ).getOrThrow()
            }
            val options = streams(info)
            val selected = if (task.downloadType == DownloadType.AUDIO_ONLY) {
                options.firstOrNull {
                    it.label.contains("audio/mp4", true) || it.parts.first().ext == "m4a"
                } ?: options.firstOrNull { it.label == task.selectedResolution }
            } else options.firstOrNull { it.label == task.selectedResolution }
            val stream = selected ?: options.firstOrNull() ?: throw IOException("无有效视频码流")
            val parts = stream.parts
            if (parts.size == 1 || (parts.size == 2 && task.downloadType != DownloadType.VIDEO_WITH_AUDIO)) {
                val one = if (parts.size == 1) parts[0] else if (task.downloadType == DownloadType.AUDIO_ONLY)
                    parts[1] else parts[0]
                return@runCatching DownloadEngine.downloadDirectMediaStream(
                    context, task, one.url, proxy, staging, outputDir, progress,
                    mapOf("Referer" to base)
                ).getOrThrow()
            }
            if (parts.size != 2 || parts.any { it.ext !in setOf("mp4", "m4a") })
                throw IOException("所选画质需要复杂分片或 WebM 混流，请选择 MP4 格式")
            staging.mkdirs(); outputDir.mkdirs()
            val chunks = parts.mapIndexed { i, _ -> File(staging, "lux_" + task.id + "_$i.part") }
            val merged = File(staging, "lux_" + task.id + "_merged.mp4")
            val dest = File(outputDir, safeTitle(task.title) + "_" + task.id.take(6) + ".mp4")
            try {
                parts.forEachIndexed { i, part ->
                    val id = task.id + "-lux$i"
                    NativeTransfer.reset(id)
                    NativeTransfer.download(context, id, part.url, chunks[i],
                        mapOf("Referer" to base), proxy
                    ) { v, speed, eta, status ->
                        progress((i * 45f + v * 0.45f).coerceAtMost(92f), speed, eta, status)
                    }.getOrThrow()
                }
                progress(95f, "", "原生封装 MP4...", TaskStatus.PROCESSING)
                FFmpegExecutor.mergeVideoAndAudio(context, chunks[0], chunks[1], merged).getOrThrow()
                if (merged.length() < 1024) throw IOException("生成的视频文件为空")
                merged.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
                chunks.forEach(::clean)
                merged.delete()
                progress(100f, "", "", TaskStatus.COMPLETED)
                dest.absolutePath
            } catch (e: Exception) {
                merged.delete(); dest.delete()
                throw e
            }
        }
    }

    fun cancel(taskId: String) {
        for (i in 0..2) NativeTransfer.cancel(taskId + "-lux$i")
    }

    private fun clean(file: File) {
        file.delete()
        File(file.absolutePath + ".aria2").delete()
        File(file.absolutePath + ".aria2source").delete()
        File(file.absolutePath + ".httpresume").delete()
    }

    private fun safeTitle(value: String) =
        value.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
            .ifBlank { "OmniVideo" }.take(60)
}
