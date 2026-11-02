package com.omni.downloader.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.webkit.CookieManager
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Lightweight native engine: no Python, yt-dlp, or bundled FFmpeg. */
object DownloadEngine {
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var ready = false
    @Volatile private var initError: String? = null

    suspend fun ensureInitialized(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        ready = Aria2Downloader.isAvailable(context)
        initError = if (ready) null else "ARM64 aria2c 下载核心缺失或无法执行"
        if (ready) Result.success(Unit) else Result.failure(IOException(initError))
    }
    fun isReady(): Boolean = ready
    fun getInitError(): String? = initError

    fun getDownloadDir(context: Context? = null): File {
        if (context != null) {
            val configured = runCatching {
                com.omni.downloader.data.repository.SettingsRepository.getInstance(context).downloadPath.value
            }.getOrDefault("")
            if (configured.isNotBlank()) {
                val dir = File(configured)
                if (dir.isDirectory || dir.mkdirs()) return dir
            }
        }
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OmniDownloader")
    }

    suspend fun fetchVideoMetadata(
        context: Context, rawUrl: String, proxyUrl: String = ""
    ): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        val url = UrlSniffer.sanitizeAndResolveUrl(rawUrl)
        val site = UrlSniffer.identifySite(url)
        try {
            when {
                MihoyoVideoExtractor.isSupported(url) -> MihoyoVideoExtractor.extract(url)
                site == "哔哩哔哩" -> BilibiliDirectExtractor.extract(url)
                site == "YouTube" -> YouTubeNativeExtractor.extract(url, proxyUrl)
                site == "TikTok" || TikTokDirectExtractor.isSupported(url) ->
                    TikTokDirectExtractor.extract(url)
                DomesticDirectExtractor.isSupported(site) ->
                    DomesticDirectExtractor.extract(url, site)
                UrlSniffer.isDirectMediaUrl(url) && !UrlSniffer.isM3u8Url(url) ->
                    Result.success(VideoMetadata(
                        url = url, title = "直接媒体文件", siteName = "直链",
                        availableVideoFormats = listOf(FormatOption(url, "原始媒体", ext = "mp4"))
                    ))
                site == "X (Twitter)" -> XPublicExtractor.extract(url)
                // Native extraction of public HTML5, OpenGraph and JSON-LD
                // media for Instagram / Facebook / Pinterest where published.
                // Requires a verified direct URL; no bundled tool or proxy site.
                else -> GenericWebVideoExtractor.extract(url)
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun downloadCoverFile(
        context: Context, coverUrl: String, title: String, proxyUrl: String = "",
        publicDir: File = getDownloadDir(context),
        onProgressUpdate: ((Float, String, String, TaskStatus) -> Unit)? = null,
        taskId: String = "cover-" + java.util.UUID.randomUUID().toString()
    ): Result<String> = withContext(Dispatchers.IO) {
        val cache = File(context.noBackupFilesDir, "image-staging").apply { mkdirs() }
        val staged = File(cache, "image_" + taskId.replace(Regex("[^a-zA-Z0-9_-]"), "_") + ".part")
        try {
            if (!coverUrl.startsWith("https://") && !coverUrl.startsWith("http://"))
                throw IOException("封面 URL 无效")
            publicDir.mkdirs()
            val headers = mutableMapOf("User-Agent" to
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
            refererFor(coverUrl).takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
            val downloaded = NativeTransfer.download(
                context, taskId, coverUrl, staged, headers, proxyUrl
            ) { p, speed, eta, status ->
                onProgressUpdate?.invoke(p, speed, eta, status)
            }
            downloaded.getOrThrow()
            val extension = ImageMagic.extension(staged)
                ?: throw IOException("封面响应不是合法图片（可能是风控或过期地址）")
            val out = uniqueFile(publicDir, safeTitle(title) + "_cover", extension)
            try {
                staged.copyTo(out, overwrite = false)
                staged.delete()
                MediaScannerConnection.scanFile(
                    context, arrayOf(out.absolutePath), null, null
                )
                onProgressUpdate?.invoke(100f, "", "", TaskStatus.COMPLETED)
                Result.success(out.absolutePath)
            } catch (e: Exception) {
                out.delete()
                throw e
            }
        } catch (e: Exception) {
            onProgressUpdate?.invoke(0f, "", "", TaskStatus.FAILED)
            Result.failure(e)
        }
    }

    suspend fun executeDownload(
        context: Context, task: DownloadTask, proxyUrl: String = "",
        onProgressUpdate: (Float, String, String, TaskStatus) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        cancelled.remove(task.id)
        NativeTransfer.reset(task.id)
        val url = UrlSniffer.sanitizeAndResolveUrl(task.url)
        val site = UrlSniffer.identifySite(url)
        val staging = File(context.noBackupFilesDir, "native-staging").apply { mkdirs() }
        val output = getDownloadDir(context).apply { mkdirs() }
        // Previously persisted Lux tasks have incompatible format identifiers.
        // They must be re-parsed, not silently downloaded at the wrong quality.
        if (task.parserId == "lux") {
            return@withContext Result.failure(IOException(
                "旧版 Lux 任务不可直接恢复，请重新解析原始链接生成原生下载任务"
            ))
        }
        if (task.parserId == "youtube-native") {
            return@withContext YouTubeNativeExtractor.download(
                context, task, staging, output, onProgressUpdate, proxyUrl
            )
        }
        if (task.parserId == "fx") {
            return@withContext XPublicExtractor.download(context, task, staging, output, onProgressUpdate, proxyUrl)
        }
        if (task.parserId == "douyin" && task.url.contains("#omni_douyin_image=")) {
            return@withContext DomesticDirectExtractor.download(context, task, staging, output, onProgressUpdate, proxyUrl)
        }
        if (task.downloadType == DownloadType.COVER) {
            return@withContext downloadCoverFile(context, task.thumbnailUrl.ifBlank { task.url }, task.title,
                proxyUrl, output, onProgressUpdate, task.id)
        }
        try {
            when {
                MihoyoVideoExtractor.isSupported(url) ->
                    MihoyoVideoExtractor.download(context, task, staging, output, onProgressUpdate, proxyUrl)
                site == "哔哩哔哩" ->
                    BilibiliDirectExtractor.download(context, task, staging, output, onProgressUpdate)
                site == "YouTube" ->
                    YouTubeNativeExtractor.download(context, task, staging, output, onProgressUpdate, proxyUrl)
                site == "TikTok" || TikTokDirectExtractor.isSupported(url) ->
                    TikTokDirectExtractor.download(context, task, staging, output, onProgressUpdate)
                DomesticDirectExtractor.isSupported(site) ->
                    DomesticDirectExtractor.download(context, task, staging, output, onProgressUpdate, proxyUrl)
                UrlSniffer.isDirectMediaUrl(url) || task.selectedResolution.startsWith("http") ||
                    task.selectedResolution == "中转直链" || task.directMediaUrl.isNotBlank() -> {
                    val target = task.directMediaUrl.ifBlank {
                        task.selectedResolution.takeIf { it.startsWith("http") } ?: url
                    }
                    downloadDirectMediaStream(context, task, target, proxyUrl, staging, output, onProgressUpdate)
                }
                site == "通用网页" || task.selectedResolution == "网页原视频" ||
                    task.selectedResolution == "网页原音频" ->
                    GenericWebVideoExtractor.download(context, task, staging, output, onProgressUpdate, proxyUrl)
                else -> Result.failure(IOException("没有可用的原生下载适配器：$site"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    internal suspend fun downloadDirectMediaStream(
        context: Context, task: DownloadTask, targetUrl: String, proxyUrl: String = "",
        stagingDir: File, publicDir: File,
        onProgressUpdate: (Float, String, String, TaskStatus) -> Unit,
        requestHeaders: Map<String, String> = emptyMap()
    ): Result<String> = withContext(Dispatchers.IO) {
        if (UrlSniffer.isM3u8Url(targetUrl)) {
            return@withContext Result.failure(IOException(
                "精简版暂不支持 HLS/M3U8 合成，不能将播放列表保存为 MP4"
            ))
        }
        val source = File(stagingDir, "native_${task.id}.media")
        val headers = mutableMapOf("User-Agent" to
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile")
        refererFor(targetUrl).takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        runCatching { CookieManager.getInstance().getCookie(targetUrl) }.getOrNull()?.let {
            if (it.isNotBlank()) headers["Cookie"] = it
        }
        headers.putAll(requestHeaders)
        val downloaded = NativeTransfer.download(
            context, task.id, targetUrl, source, headers, proxyUrl, onProgressUpdate
        )
        if (downloaded.isFailure) return@withContext Result.failure(downloaded.exceptionOrNull()!!)
        if (cancelled.contains(task.id)) return@withContext Result.failure(IOException("任务已取消"))
        if (!isValidMediaFile(source)) {
            source.delete()
            return@withContext Result.failure(IOException("下载的不是有效媒体文件，可能是过期链接或防盗链响应"))
        }
        val ext = when (task.downloadType) {
            DownloadType.AUDIO_ONLY -> if (task.audioFormat.ext == "m4a") "m4a" else {
                source.delete()
                return@withContext Result.failure(IOException("精简版只支持 M4A 原声轨导出"))
            }
            DownloadType.GIF -> {
                source.delete()
                return@withContext Result.failure(IOException("精简版不支持视频转 GIF"))
            }
            else -> detectMediaExtension(source)
        }
        val out = uniqueFile(publicDir, safeTitle(task.title), ext)
        val processed = File(stagingDir, "processed_${task.id}.$ext")
        try {
            when (task.downloadType) {
                DownloadType.VIDEO_ONLY -> {
                    onProgressUpdate(95f, "", "无损提取视频轨...", TaskStatus.PROCESSING)
                    FFmpegExecutor.stripAudio(context, source, processed).getOrThrow()
                    processed.copyTo(out, overwrite = true)
                }
                DownloadType.AUDIO_ONLY -> {
                    onProgressUpdate(95f, "", "无损提取 M4A 音轨...", TaskStatus.PROCESSING)
                    FFmpegExecutor.extractAudio(context, source, processed, "m4a").getOrThrow()
                    processed.copyTo(out, overwrite = true)
                }
                else -> source.copyTo(out, overwrite = true)
            }
            if (out.length() < 1024L || cancelled.contains(task.id)) throw IOException("媒体导出失败或已取消")
            MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), null, null)
            onProgressUpdate(100f, "", "", TaskStatus.COMPLETED)
            Result.success(out.absolutePath)
        } catch (e: Exception) {
            out.delete()
            onProgressUpdate(0f, "", "", TaskStatus.FAILED)
            Result.failure(e)
        } finally {
            processed.delete()
            source.delete()
            File(source.absolutePath + ".aria2").delete()
            File(source.absolutePath + ".aria2source").delete()
            File(source.absolutePath + ".httpresume").delete()
        }
    }

    fun cancelTask(taskId: String) {
        cancelled.add(taskId)
        NativeTransfer.cancel(taskId)
    }

    private fun safeTitle(value: String): String =
        value.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "OmniVideo" }.take(64)
    private fun uniqueFile(dir: File, name: String, ext: String): File {
        var result = File(dir, "$name.$ext")
        var suffix = 1
        while (result.exists()) result = File(dir, "${name}_${suffix++}.$ext")
        return result
    }
    private fun refererFor(url: String): String {
        val host = runCatching { java.net.URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return when {
            host.endsWith(".bilivideo.com") || host.endsWith(".bilibili.com") ||
                host.endsWith(".hdslb.com") -> "https://www.bilibili.com/"
            host.endsWith(".mihoyo.com") -> "https://baike.mihoyo.com/"
            host.endsWith(".miyoushe.com") -> "https://www.miyoushe.com/"
            host.endsWith(".douyin.com") || host.endsWith(".snssdk.com") ||
                host.endsWith(".douyinpic.com") || host.endsWith(".douyinvod.com") -> "https://www.douyin.com/"
            host.endsWith(".yximgs.com") -> "https://www.kuaishou.com/"
            host.endsWith(".xhscdn.com") -> "https://www.xiaohongshu.com/"
            host.endsWith(".twimg.com") -> "https://x.com/"
            else -> ""
        }
    }

    /** Preserve actual stream containers instead of renaming WebM/Ogg/MP3 to .mp4. */
    private fun detectMediaExtension(file: File): String {
        val header = ByteArray(24)
        val count = runCatching { FileInputStream(file).use { it.read(header) } }.getOrDefault(0)
        if (count < 12) return "bin"
        val ascii = String(header, 0, count, Charsets.ISO_8859_1)
        return when {
            ascii.startsWith("ID3") -> "mp3"
            header[0] == 0xff.toByte() && (header[1].toInt() and 0xe0) == 0xe0 -> "mp3"
            ascii.startsWith("OggS") -> "ogg"
            ascii.startsWith("RIFF") && ascii.substring(8, 12) == "WAVE" -> "wav"
            ascii.startsWith("RIFF") && ascii.substring(8, 12) == "AVI " -> "avi"
            header[0] == 0x1a.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xdf.toByte() && header[3] == 0xa3.toByte() -> "webm"
            ascii.substring(4, 8) == "ftyp" -> "mp4"
            header[0] == 0x47.toByte() -> "ts"
            else -> "bin"
        }
    }

    fun isValidMediaFile(file: File): Boolean {
        if (!file.isFile || file.length() < 1024L) return false
        val b = ByteArray(32)
        val count = runCatching { FileInputStream(file).use { it.read(b) } }.getOrDefault(0)
        if (count < 12) return false
        val text = String(b, 0, count, Charsets.ISO_8859_1).trimStart().lowercase(Locale.ROOT)
        if (text.startsWith("<") || text.startsWith("{") ||
            text.startsWith("[") || text.startsWith("#extm3u")) return false
        return (b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
            b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()) ||
            (b[0] == 0x1a.toByte() && b[1] == 0x45.toByte() &&
            b[2] == 0xdf.toByte() && b[3] == 0xa3.toByte()) ||
            b[0] == 0x47.toByte() ||
            (b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) ||
            (b[0] == 0xff.toByte() && (b[1].toInt() and 0xe0) == 0xe0) ||
            (b[0] == 'O'.code.toByte() && b[1] == 'g'.code.toByte() &&
            b[2] == 'g'.code.toByte() && b[3] == 'S'.code.toByte()) ||
            (b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte())
    }

    suspend fun updateEngine(context: Context): Result<String> = withContext(Dispatchers.IO) {
        if (Aria2Downloader.isAvailable(context))
            Result.success("aria2 下载核心就绪；平台解析均为轻量原生模块")
        else Result.failure(IOException("aria2 原生模块不完整，请重新安装"))
    }
}
