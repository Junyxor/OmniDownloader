package com.omni.downloader.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.webkit.CookieManager
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Native JSON-only YouTube adapter: no Lux, yt-dlp, JS runtime, or binary extractor.
 * Downloads are delegated exclusively to aria2 via DownloadEngine.
 *
 * Restricted to complete progressive video and direct M4A audio links.
 * SignatureCipher, n challenges, PO tokens and protected/adaptive-only media
 * are reported as unsupported, never falsely labelled as downloadable.
 */
object YouTubeNativeExtractor {
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    fun cancel(taskId: String) {
        cancelled.add(taskId)
        NativeTransfer.cancel(taskId + "-yt-video")
        NativeTransfer.cancel(taskId + "-yt-audio")
    }


    /** Only exact YouTube hosts are trusted; a query or subdomain suffix is not enough. */
    internal fun isYouTubeHost(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("https", "http") || uri.userInfo != null) return false
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        return host in setOf(
            "youtube.com", "m.youtube.com", "music.youtube.com",
            "youtube-nocookie.com", "youtu.be"
        )
    }

    /** Discard share/tracking params, preserving only the requested video ID. */
    internal fun canonicalWatchUrl(url: String): String? =
        videoId(url)?.let { "https://www.youtube.com/watch?v=$it" }

    internal fun videoId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!isYouTubeHost(url)) return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val path = uri.path.orEmpty().trim('/')
        val id = when {
            host == "youtu.be" -> path.substringBefore('/')
            host in setOf("youtube.com", "m.youtube.com",
                "music.youtube.com", "youtube-nocookie.com") -> when {
                path == "watch" -> uri.rawQuery.orEmpty().split("&")
                    .firstOrNull { it.startsWith("v=") }?.substringAfter("=")
                path.startsWith("shorts/") || path.startsWith("live/") ||
                    path.startsWith("embed/") -> path.substringAfter('/').substringBefore('/')
                else -> null
            }
            else -> null
        }
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }

    internal fun jsonAfter(text: String, marker: String): String? {
        val at = text.indexOf(marker)
        if (at < 0) return null
        val start = text.indexOf('{', at + marker.length)
        if (start < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> quoted = false
                }
            } else {
                when (ch) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    internal fun parsePlayer(json: String, id: String): Result<VideoMetadata> = runCatching {
        val root = JSONObject(json)
        val status = root.optJSONObject("playabilityStatus")
        val state = status?.optString("status").orEmpty()
        if (state.isNotBlank() && state != "OK") {
            throw IOException("YouTube 播放受限：" +
                status?.optString("reason").orEmpty().ifBlank { state })
        }
        val details = root.optJSONObject("videoDetails")
            ?: throw IOException("YouTube 未返回作品信息")
        if (details.optString("videoId") != id) {
            throw IOException("YouTube 作品 ID 不匹配")
        }
        if (details.optBoolean("isLiveContent", false)) {
            throw IOException("暂不支持直播和 HLS")
        }
        val streams = root.optJSONObject("streamingData")
            ?: throw IOException("YouTube 没有公开播放流：可能需要签名或 PO Token")
        val progressive = streams.optJSONArray("formats") ?: JSONArray()
        val adaptive = streams.optJSONArray("adaptiveFormats") ?: JSONArray()
        val videos = mutableListOf<FormatOption>()
        val audios = mutableListOf<FormatOption>()
        var cipherCount = 0

        fun direct(entry: JSONObject): String? {
            val url = entry.optString("url")
            if (url.isBlank()) {
                if (entry.has("signatureCipher") || entry.has("cipher")) cipherCount++
                return null
            }
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.userInfo != null || uri.host.isNullOrBlank()) return null
            return url
        }
        for (i in 0 until progressive.length()) {
            val row = progressive.optJSONObject(i) ?: continue
            val mime = row.optString("mimeType")
            if (!mime.startsWith("video/mp4") && !mime.startsWith("video/webm")) continue
            if (row.optString("url").isBlank() &&
                (row.has("signatureCipher") || row.has("cipher"))) cipherCount++
            if (row.optString("audioQuality").isBlank() &&
                row.optInt("audioChannels", 0) == 0) continue
            val streamUrl = direct(row) ?: continue
            val height = row.optInt("height")
            videos += FormatOption(
                formatId = streamUrl,
                resolutionLabel = row.optString("qualityLabel")
                    .ifBlank { if (height > 0) height.toString() + "p" else "完整视频" },
                width = row.optInt("width"), height = height,
                ext = if (mime.startsWith("video/webm")) "webm" else "mp4",
                note = "YouTube 官方返回的完整音视频流"
            )
        }
        for (i in 0 until adaptive.length()) {
            val row = adaptive.optJSONObject(i) ?: continue
            if (!row.optString("mimeType").startsWith("audio/mp4")) continue
            if (row.optString("url").isBlank() &&
                (row.has("signatureCipher") || row.has("cipher"))) cipherCount++
            val streamUrl = direct(row) ?: continue
            val kbps = row.optInt("bitrate") / 1000
            audios += FormatOption(
                formatId = streamUrl,
                resolutionLabel = if (kbps > 0) kbps.toString() + "kbps M4A" else "M4A 音频",
                ext = "m4a", isAudioOnly = true,
                note = "YouTube 官方返回的音频轨"
            )
        }
        // Adaptive 1080p/4K streams can be remuxed with Android MediaMuxer
        // when video is MP4 AVC/HEVC and a native M4A/AAC track is available.
        // AV1/VP9 and container conversions are intentionally not advertised.
        if (audios.isNotEmpty()) {
            for (i in 0 until adaptive.length()) {
                val row = adaptive.optJSONObject(i) ?: continue
                val mime = row.optString("mimeType")
                if (!mime.startsWith("video/mp4") ||
                    !(mime.contains("avc1", ignoreCase = true) ||
                      mime.contains("hvc1", ignoreCase = true) ||
                      mime.contains("hev1", ignoreCase = true))) continue
                if (row.optString("url").isBlank() &&
                    (row.has("signatureCipher") || row.has("cipher"))) cipherCount++
                val url = direct(row) ?: continue
                val height = row.optInt("height")
                val fps = row.optInt("fps")
                val itag = row.optInt("itag")
                val label = (if (height > 0) height.toString() + "p" else "视频轨") +
                    (if (fps > 30) fps.toString() else "") +
                    " DASH" + (if (itag > 0) " (itag " + itag + ")" else "")
                videos += FormatOption(
                    formatId = url, resolutionLabel = label,
                    width = row.optInt("width"), height = height, fps = fps,
                    ext = "mp4", isVideoOnly = true,
                    note = "aria2 分轨下载 + Android 系统 MP4 无损合并"
                )
            }
        }
        if (videos.isEmpty() && audios.isEmpty()) {
            throw IOException(if (cipherCount > 0)
                "YouTube 流需要解签名；轻量原生实现暂未支持"
            else "YouTube 未提供直接流；可能需要 n 参数、PO Token 或登录")
        }
        val thumbs = details.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumb = thumbs?.optJSONObject((thumbs.length() - 1).coerceAtLeast(0))
            ?.optString("url").orEmpty()
        val duration = details.optString("lengthSeconds").toLongOrNull()
            ?.let { (it / 60).toString() + ":" + (it % 60).toString().padStart(2, '0') }.orEmpty()
        VideoMetadata(
            url = "https://www.youtube.com/watch?v=$id",
            title = details.optString("title").ifBlank { "YouTube $id" },
            author = details.optString("author"),
            durationText = duration, thumbnailUrl = thumb,
            siteName = "YouTube",
            availableVideoFormats = videos.distinctBy { it.formatId }
                .sortedByDescending { it.height },
            availableAudioFormats = audios.distinctBy { it.formatId }
                .sortedByDescending { it.resolutionLabel.substringBefore("kbps").toIntOrNull() ?: 0 },
            parserId = "youtube-native"
        )
    }

    private fun client(proxy: String): OkHttpClient {
        val b = OkHttpClient.Builder().followRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        if (proxy.isNotBlank()) {
            val uri = URI(if ("://" in proxy) proxy else "http://$proxy")
            val host = uri.host ?: throw IOException("代理地址不合法")
            val port = uri.port.takeIf { it in 1..65535 } ?: throw IOException("代理端口不合法")
            val type = if (uri.scheme in setOf("socks5", "socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
            b.proxy(Proxy(type, InetSocketAddress(host, port)))
        }
        return b.build()
    }

    suspend fun extract(url: String, proxy: String = ""): Result<VideoMetadata> =
        withContext(Dispatchers.IO) {
            runCatching {
                val canonical = canonicalWatchUrl(url)
                    ?: throw IOException("不是 YouTube 视频或 Shorts 链接")
                val id = videoId(canonical)
                    ?: throw IOException("YouTube 视频 ID 无效")
                val http = client(proxy)
                val cookies = runCatching {
                    CookieManager.getInstance().getCookie("https://www.youtube.com/")
                }.getOrNull().orEmpty()
                val request = Request.Builder().url(canonical)
                    .header("User-Agent", UA)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .apply { if (cookies.isNotBlank()) header("Cookie", cookies) }
                    .build()
                val html = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("YouTube 网页 HTTP " + response.code)
                    val body = response.body ?: throw IOException("YouTube 网页无响应")
                    if (body.contentLength() > 3 * 1024 * 1024)
                        throw IOException("YouTube 页面超过 3 MB 上限")
                    body.charStream().use { it.readText().take(3 * 1024 * 1024) }
                }
                val initial = jsonAfter(html, "ytInitialPlayerResponse")
                if (initial != null) {
                    val parsed = parsePlayer(initial, id)
                    if (parsed.isSuccess) return@runCatching parsed.getOrThrow()
                }
                // Read the current first-party Innertube key/version from HTML
                // instead of packaging an external extractor or stale API key.
                val cfg = jsonAfter(html, "ytcfg.set(")?.let(::JSONObject)
                    ?: throw IOException("YouTube 网页没有提供播放器配置")
                val key = cfg.optString("INNERTUBE_API_KEY")
                val version = cfg.optString("INNERTUBE_CLIENT_VERSION")
                if (key.isBlank() || version.isBlank())
                    throw IOException("YouTube 网页缺少客户端参数")
                val info = JSONObject().put("clientName", "WEB")
                    .put("clientVersion", version).put("hl", "en").put("gl", "US")
                cfg.optString("VISITOR_DATA").takeIf { it.isNotBlank() }
                    ?.let { info.put("visitorData", it) }
                val payload = JSONObject().put("videoId", id)
                    .put("context", JSONObject().put("client", info))
                val api = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?key=$key")
                    .header("User-Agent", UA)
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", canonical)
                    .header("X-Youtube-Client-Name", "1")
                    .header("X-Youtube-Client-Version", version)
                    .apply { if (cookies.isNotBlank()) header("Cookie", cookies) }
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                val body = http.newCall(api).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("YouTube 播放接口 HTTP " + response.code)
                    response.body?.string() ?: throw IOException("YouTube 播放接口响应为空")
                }
                parsePlayer(body, id).getOrThrow()
            }
        }

    suspend fun download(
        context: Context, task: DownloadTask, staging: File, output: File,
        progress: (Float, String, String, TaskStatus) -> Unit, proxy: String
    ): Result<String> = withContext(Dispatchers.IO) {
        cancelled.remove(task.id)
        val videoTask = task.id + "-yt-video"
        val audioTask = task.id + "-yt-audio"
        NativeTransfer.reset(videoTask)
        NativeTransfer.reset(audioTask)
        val metadata = extract(task.url, proxy).getOrElse {
            return@withContext Result.failure(it)
        }
        val formats = if (task.downloadType == DownloadType.AUDIO_ONLY)
            metadata.availableAudioFormats else metadata.availableVideoFormats
        val selected = formats.firstOrNull { it.resolutionLabel == task.selectedResolution }
            ?: formats.firstOrNull()
            ?: return@withContext Result.failure(IOException(
                if (task.downloadType == DownloadType.AUDIO_ONLY)
                    "此视频没有直接可用的 M4A 音轨"
                else "此视频没有可用的完整视频或可混流的 AVC/HEVC + AAC 轨道"
            ))
        val headers = mapOf(
            "Referer" to "https://www.youtube.com/",
            "User-Agent" to UA
        )
        if (selected.isVideoOnly && task.downloadType == DownloadType.VIDEO_WITH_AUDIO) {
            val audio = metadata.availableAudioFormats.firstOrNull()
                ?: return@withContext Result.failure(IOException("高画质 DASH 缺失兼容 AAC 音轨"))
            val videoFile = File(staging, "yt_" + task.id + "_video.m4v")
            val audioFile = File(staging, "yt_" + task.id + "_audio.m4a")
            val mergedFile = File(staging, "yt_" + task.id + "_merged.mp4")
            if (cancelled.contains(task.id))
                return@withContext Result.failure(IOException("下载已取消"))

            val v = NativeTransfer.download(
                context, videoTask, selected.formatId, videoFile, headers, proxy
            ) { pct, speed, eta, status ->
                progress((pct * 0.69f).coerceIn(1f, 69f), speed, eta, status)
            }
            if (v.isFailure) return@withContext Result.failure(v.exceptionOrNull()!!)
            if (!DownloadEngine.isValidMediaFile(videoFile))
                return@withContext Result.failure(IOException("DASH 视频轨不是有效媒体文件"))
            if (cancelled.contains(task.id))
                return@withContext Result.failure(IOException("下载已取消"))

            val a = NativeTransfer.download(
                context, audioTask, audio.formatId, audioFile, headers, proxy
            ) { pct, speed, eta, status ->
                progress((70f + pct * 0.23f).coerceIn(70f, 93f), speed, eta, status)
            }
            if (a.isFailure) return@withContext Result.failure(a.exceptionOrNull()!!)
            if (!DownloadEngine.isValidMediaFile(audioFile))
                return@withContext Result.failure(IOException("DASH AAC 音轨不是有效媒体文件"))
            if (cancelled.contains(task.id))
                return@withContext Result.failure(IOException("下载已取消"))
            progress(95f, "", "使用 Android MediaMuxer 合并音视频...", TaskStatus.PROCESSING)
            val merged = FFmpegExecutor.mergeVideoAndAudio(
                context, videoFile, audioFile, mergedFile
            )
            if (merged.isFailure) return@withContext Result.failure(
                IOException("系统原生 MP4 混流失败：" +
                    merged.exceptionOrNull()?.message.orEmpty(), merged.exceptionOrNull())
            )
            if (!DownloadEngine.isValidMediaFile(mergedFile) || cancelled.contains(task.id)) {
                mergedFile.delete()
                return@withContext Result.failure(IOException("合并后文件无效或任务已取消"))
            }
            val base = task.title.replace(Regex("""[\\/:*?"<>|]"""), "_")
                .trim().ifBlank { "YouTube" }.take(64)
            var destination = File(output, base + ".mp4")
            var seq = 1
            while (destination.exists()) {
                destination = File(output, base + "_" + seq++ + ".mp4")
            }
            try {
                mergedFile.copyTo(destination, overwrite = false)
                MediaScannerConnection.scanFile(
                    context, arrayOf(destination.absolutePath), arrayOf("video/mp4"), null
                )
                videoFile.delete()
                audioFile.delete()
                mergedFile.delete()
                progress(100f, "", "", TaskStatus.COMPLETED)
                return@withContext Result.success(destination.absolutePath)
            } catch (e: Exception) {
                destination.delete()
                return@withContext Result.failure(e)
            }
        }
        if (cancelled.contains(task.id))
            return@withContext Result.failure(IOException("下载已取消"))
        DownloadEngine.downloadDirectMediaStream(
            context, task, selected.formatId, proxy, staging, output, progress,
            headers
        )
    }
}
