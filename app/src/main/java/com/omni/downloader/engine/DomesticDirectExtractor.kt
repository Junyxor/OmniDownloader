package com.omni.downloader.engine

import android.content.Context
import android.util.Log
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 国内短视频平台（抖音、快手、小红书）原生轻量直接解析器
 * 提供免 Python、毫秒级的无水印视频元数据嗅探与极速流式下载
 */
object DomesticDirectExtractor {

    private const val TAG = "DomesticExtractor"

    private const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            private val sessionCookies = mutableListOf<Cookie>()
            @Synchronized
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                sessionCookies.removeAll { old -> old.expiresAt <= System.currentTimeMillis() || cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
                sessionCookies.addAll(cookies)
            }
            @Synchronized
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                sessionCookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
        })
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    // 缓存已解析出的直链以供下载时直接复用
    private val directStreamCache = ConcurrentHashMap<String, DirectStreamInfo>()

    data class DirectStreamInfo(
        val videoUrl: String,
        val headers: Map<String, String> = emptyMap()
    )

    /**
     * 判断是否属于本解析器支持的国内平台
     */
    fun isSupported(site: String): Boolean {
        return site == "抖音" || site == "快手" || site == "小红书"
    }

    /**
     * 解析视频元数据
     */
    suspend fun extract(url: String, site: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        try {
            when (site) {
                "抖音" -> extractDouyin(url)
                "快手" -> extractKuaishou(url)
                "小红书" -> extractXiaohongshu(url)
                else -> Result.failure(IllegalArgumentException("不支持的国内平台: $site"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 $site 异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 抖音无水印直接解析
     */
    private fun extractDouyin(rawUrl: String): Result<VideoMetadata> {
        val redirected = followRedirects(rawUrl, MOBILE_UA)
        val id = douyinIdFromUrl(redirected).ifBlank { douyinIdFromUrl(rawUrl) }
        if (id.isBlank()) {
            return Result.failure(IllegalArgumentException(
                "抖音短链接未返回有效作品 ID，请检查网络后重试，不会用其它推荐作品替代"
            ))
        }
        val isImagePost = listOf("/note/", "/slide/", "/slides/").any {
            it in redirected || it in rawUrl
        }
        val shareUrl = if (isImagePost) "https://www.iesdouyin.com/share/slides/$id"
            else "https://www.iesdouyin.com/share/video/$id"

        // Desktop www.douyin.com/video/{id} is frequently a CSR shell.
        // Mobile share pages still expose item-specific SSR JSON.
        val pages = listOf(shareUrl, redirected).distinct()
        for (page in pages) {
            val html = fetchHtml(page, MOBILE_UA)
            if (html.isNotBlank()) {
                val data = DouyinStructuredParser.parseHtml(html, id, shareUrl)
                if (data != null) {
                    cacheDouyin(data)
                    return Result.success(data)
                }
            }
        }

        // Public mobile feeds can differ by CDN / device; require exact ID match.
        val ua = "com.ss.android.ugc.aweme/290101 (Linux; U; Android 10; zh_CN; Pixel 4; Build/QQ3A.200805.001)"
        val endpoints = listOf(
            "https://api5-normal-c-hl.amemv.com/aweme/v1/feed/",
            "https://aweme.snssdk.com/aweme/v1/feed/",
            "https://api.amemv.com/aweme/v1/feed/"
        )
        for (endpoint in endpoints) {
            val req = Request.Builder().url(endpoint + "?aweme_id=$id")
                .header("User-Agent", ua)
                .header("Referer", shareUrl)
                .build()
            val responseText = runCatching {
                httpClient.newCall(req).execute().use { response ->
                    if (response.isSuccessful) response.body?.string().orEmpty() else ""
                }
            }.getOrDefault("")
            if (responseText.isNotBlank()) {
                val data = DouyinStructuredParser.parse(responseText, id, shareUrl)
                if (data != null) {
                    cacheDouyin(data)
                    return Result.success(data)
                }
            }
        }
        return Result.failure(IOException(
            "抖音作品 $id 已识别，但移动端 SSR 与 Feed 均未提供匹配的视频或图文信息；可能需要登录或接口临时限制"
        ))
    }

    private fun cacheDouyin(meta: VideoMetadata) {
        val direct = meta.availableVideoFormats.firstOrNull()?.formatId.orEmpty()
        if (direct.startsWith("http")) directStreamCache[meta.url] = DirectStreamInfo(
            videoUrl = direct,
            headers = mapOf("User-Agent" to MOBILE_UA, "Referer" to "https://www.douyin.com/")
        )
    }

    /** Only work IDs in explicit Douyin routes or query parameters can be used. */
    internal fun douyinIdFromUrl(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return ""
        val path = uri.path.orEmpty()
        val id = Regex("""/(?:share/)?(?:video|note|slide|slides|aweme)/(\d{17,22})(?:/|$)""")
            .find(path)?.groupValues?.get(1)
        if (!id.isNullOrBlank()) return id
        return Regex("""(?:^|&)(?:aweme_id|item_ids|item_id)=(\d{17,22})(?:&|$)""")
            .find(uri.rawQuery.orEmpty())?.groupValues?.get(1).orEmpty()
    }

    /**
     * 快手无水印直接解析
     */
    private fun extractKuaishou(rawUrl: String): Result<VideoMetadata> {
        val resolvedUrl = followRedirects(rawUrl, MOBILE_UA)
        Log.d(TAG, "快手重定向目标: $resolvedUrl")

        val html = fetchHtml(resolvedUrl, MOBILE_UA)
        if (html.isEmpty()) {
            return Result.failure(Exception("无法获取快手分享页面内容"))
        }

        var videoUrl = ""
        var title = "快手短视频"
        var author = "快手创作者"
        var coverUrl = ""

        // 1. 匹配无水印视频直链
        val patterns = listOf(
            Pattern.compile(""""srcNoMark"\s*:\s*"([^"]+)""""),
            Pattern.compile(""""playUrl"\s*:\s*"([^"]+)""""),
            Pattern.compile(""""photoUrl"\s*:\s*"([^"]+)""""),
            Pattern.compile("""<video[^>]+src="([^"]+)""""),
            Pattern.compile("""https?://[a-zA-Z0-9_\-.]+(?:kwaicdn|yximgs)\.com/[^\s"']+\.mp4""")
        )

        for (p in patterns) {
            val m = p.matcher(html)
            if (m.find()) {
                val candidate = (if (m.groupCount() >= 1) m.group(1) else m.group(0)) ?: ""
                if (candidate.isNotEmpty()) {
                    videoUrl = candidate.replace("""\/""", "/")
                    break
                }
            }
        }

        // 标题与创作者
        val titleMatcher = Pattern.compile(""""caption"\s*:\s*"([^"]*)"""").matcher(html)
        if (titleMatcher.find()) {
            title = titleMatcher.group(1)?.trim() ?: title
        } else {
            val docTitle = Pattern.compile("""<title>(.*?)</title>""").matcher(html)
            if (docTitle.find()) {
                title = docTitle.group(1)?.replace(" - 快手", "")?.trim() ?: title
            }
        }

        val authorMatcher = Pattern.compile(""""userName"\s*:\s*"([^"]*)"""").matcher(html)
        if (authorMatcher.find()) {
            author = authorMatcher.group(1)?.trim() ?: author
        }

        val coverMatcher = Pattern.compile(""""poster"\s*:\s*"([^"]+)"""").matcher(html)
        if (coverMatcher.find()) {
            coverUrl = coverMatcher.group(1)?.replace("""\/""", "/") ?: ""
        }

        if (videoUrl.isNotEmpty()) {
            directStreamCache[resolvedUrl] = DirectStreamInfo(
                videoUrl = videoUrl,
                headers = mapOf("User-Agent" to MOBILE_UA, "Referer" to "https://www.kuaishou.com/")
            )
            return Result.success(
                VideoMetadata(
                    url = resolvedUrl,
                    title = title,
                    author = author,
                    thumbnailUrl = coverUrl,
                    siteName = "快手",
                    availableVideoFormats = listOf(
                        FormatOption(
                            formatId = videoUrl,
                            resolutionLabel = "原画 (无水印)",
                            ext = "mp4",
                            note = "极速直连原画"
                        )
                    )
                )
            )
        }

        return Result.failure(Exception("未能从快手页面提取到有效视频直链，将尝试通用引擎"))
    }

    /**
     * 小红书无水印直接解析
     */
    private fun extractXiaohongshu(rawUrl: String): Result<VideoMetadata> {
        val resolvedUrl = followRedirects(rawUrl, DESKTOP_UA)
        Log.d(TAG, "小红书重定向目标: $resolvedUrl")

        val html = fetchHtml(resolvedUrl, DESKTOP_UA)
        if (html.isEmpty()) {
            return Result.failure(Exception("无法获取小红书页面内容"))
        }

        var videoUrl = ""
        var title = "小红书视频"
        var author = "小红书作者"
        var coverUrl = ""

        // 1. 尝试解析 window.__INITIAL_STATE__
        val stateMatcher = Pattern.compile("""window\.__INITIAL_STATE__\s*=\s*(.*?)</script>""").matcher(html)
        if (stateMatcher.find()) {
            val stateStr = stateMatcher.group(1)?.trim() ?: ""
            // 规范化 JSON（小红书 SSR 可能包含 undefined）
            val validJsonStr = stateStr.replace("undefined", "null")
            try {
                // 查找 masterUrl
                val masterMatcher = Pattern.compile(""""masterUrl"\s*:\s*"([^"]+)"""").matcher(validJsonStr)
                if (masterMatcher.find()) {
                    videoUrl = masterMatcher.group(1)?.replace("""\/""", "/") ?: ""
                }

                // 查找 originVideoKey
                if (videoUrl.isEmpty()) {
                    val keyMatcher = Pattern.compile(""""originVideoKey"\s*:\s*"([^"]+)"""").matcher(validJsonStr)
                    if (keyMatcher.find()) {
                        val key = keyMatcher.group(1)
                        if (!key.isNullOrEmpty()) {
                            videoUrl = "https://sns-video-bd.xhscdn.com/$key"
                        }
                    }
                }

                val titleMatcher = Pattern.compile(""""title"\s*:\s*"([^"]*)"""").matcher(validJsonStr)
                if (titleMatcher.find() && !titleMatcher.group(1).isNullOrBlank()) {
                    title = titleMatcher.group(1)!!
                } else {
                    val descMatcher = Pattern.compile(""""desc"\s*:\s*"([^"]*)"""").matcher(validJsonStr)
                    if (descMatcher.find() && !descMatcher.group(1).isNullOrBlank()) {
                        title = descMatcher.group(1)!!
                    }
                }

                val userMatcher = Pattern.compile(""""nickname"\s*:\s*"([^"]*)"""").matcher(validJsonStr)
                if (userMatcher.find()) {
                    author = userMatcher.group(1) ?: author
                }

                val coverMatcher = Pattern.compile(""""firstFrameFileid"\s*:\s*"([^"]+)"""").matcher(validJsonStr)
                if (coverMatcher.find()) {
                    coverUrl = "https://sns-webpic-qc.xhscdn.com/${coverMatcher.group(1)}"
                }
            } catch (ignored: Exception) {
            }
        }

        // 备选正则探测
        if (videoUrl.isEmpty()) {
            val cdnMatcher = Pattern.compile("""https?://[a-zA-Z0-9_\-.]*xhscdn\.com/[^\s"']+\.mp4""").matcher(html)
            if (cdnMatcher.find()) {
                videoUrl = cdnMatcher.group(0) ?: ""
            }
        }

        if (videoUrl.isNotEmpty()) {
            directStreamCache[resolvedUrl] = DirectStreamInfo(
                videoUrl = videoUrl,
                headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://www.xiaohongshu.com/")
            )
            return Result.success(
                VideoMetadata(
                    url = resolvedUrl,
                    title = title,
                    author = author,
                    thumbnailUrl = coverUrl,
                    siteName = "小红书",
                    availableVideoFormats = listOf(
                        FormatOption(
                            formatId = videoUrl,
                            resolutionLabel = "原画 (无水印)",
                            ext = "mp4",
                            note = "极速直连原画"
                        )
                    )
                )
            )
        }

        return Result.failure(Exception("未能从小红书页面提取到视频直链，将尝试通用引擎"))
    }

    /**
     * 执行国内平台原生直连极速流式下载
     */
    suspend fun download(
        context: Context,
        task: DownloadTask,
        stagingDir: File,
        publicDir: File,
        onProgressUpdate: (progress: Float, speed: String, eta: String, status: TaskStatus) -> Unit,
        proxyUrl: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        // Image posts are collections: re-parse the source to refresh expiring image URLs
        // rather than treating each image as a video MP4.
        val selectedImage = Regex("""(?:^|[?&])omni_douyin_image=(\d+)""")
            .find(task.url.substringAfter('#', ""))?.groupValues?.get(1)?.toIntOrNull()
        if (selectedImage != null) {
            val original = task.url.substringBefore('#')
            val info = extractDouyin(original).getOrElse {
                return@withContext Result.failure(it)
            }
            val asset = info.imageAssets.getOrNull(selectedImage)
                ?: return@withContext Result.failure(IOException("图集第 ${selectedImage + 1} 项不存在，请重新解析"))
            val mediaUrls = (listOf(asset.url) + asset.mirrors).distinct()
            if (mediaUrls.isEmpty()) {
                return@withContext Result.failure(IOException("图集图片地址失效，请重新解析"))
            }
            // Gallery downloads must use the existing aria2 transfer core, not
            // the legacy OkHttp cover downloader or a separate media binary.
            val staged = File(stagingDir, "douyin_image_${task.id}.part")
            val headers = mapOf("User-Agent" to MOBILE_UA, "Referer" to "https://www.douyin.com/")
            var lastError: Throwable? = null
            for (mediaUrl in mediaUrls) {
                if (!mediaUrl.startsWith("https://") && !mediaUrl.startsWith("http://")) continue
                val downloaded = Aria2Downloader.download(
                    context, task.id, mediaUrl, staged, headers, proxyUrl, onProgressUpdate
                )
                if (downloaded.isFailure) {
                    lastError = downloaded.exceptionOrNull()
                    if (Aria2Downloader.isCancelled(task.id)) {
                        return@withContext Result.failure(IOException("图集下载已取消", lastError))
                    }
                    continue
                }
                val extension = ImageMagic.extension(staged)
                if (extension == null) {
                    staged.delete()
                    lastError = IOException("服务器未返回合法图片（可能是 HTML 风控页或失效直链）")
                    continue
                }
                val safeName = task.title.replace(Regex("""[\\/:*?"<>|]"""), "_")
                    .trim().ifBlank { "Douyin_${selectedImage + 1}" }.take(90)
                var output = File(publicDir, "$safeName.$extension")
                var duplicate = 1
                while (output.exists()) {
                    output = File(publicDir, "${safeName}_${duplicate++}.$extension")
                }
                try {
                    staged.copyTo(output, overwrite = false)
                    staged.delete()
                    android.media.MediaScannerConnection.scanFile(
                        context, arrayOf(output.absolutePath), arrayOf("image/$extension"), null
                    )
                    onProgressUpdate(100f, "", "", TaskStatus.COMPLETED)
                    return@withContext Result.success(output.absolutePath)
                } catch (e: Exception) {
                    output.delete()
                    return@withContext Result.failure(e)
                }
            }
            return@withContext Result.failure(IOException(
                "图集第 ${selectedImage + 1} 张图片下载失败，已尝试所有地址",
                lastError
            ))
        }
        val cleanUrl = UrlSniffer.sanitizeAndResolveUrl(task.url)
        val site = UrlSniffer.identifySite(cleanUrl)
        val cached = directStreamCache[cleanUrl] ?: directStreamCache[task.url]
        val mediaUrl = task.directMediaUrl.ifBlank {
            task.selectedResolution.takeIf { it.startsWith("http") }
                ?: cached?.videoUrl?.takeIf { it.isNotBlank() }
                ?: extract(cleanUrl, site).getOrElse { return@withContext Result.failure(it) }
                    .availableVideoFormats.firstOrNull()?.formatId.orEmpty()
        }
        if (!mediaUrl.startsWith("http")) {
            return@withContext Result.failure(Exception("未找到有效的国内媒体直链，请重新解析链接"))
        }
        val headers = DomesticMediaSource.headers(site) + cached?.headers.orEmpty() + task.directMediaHeaders
        // 共用已有的取消、重试、媒体校验和后处理，不能将视频简单改名为音频。
        DownloadEngine.downloadDirectMediaStream(
            context, task, mediaUrl, proxyUrl, stagingDir, publicDir, onProgressUpdate, headers
        )
    }

    private fun followRedirects(startUrl: String, userAgent: String): String {
        return try {
            val req = Request.Builder()
                .url(startUrl)
                .header("User-Agent", userAgent)
                .build()
            httpClient.newCall(req).execute().use { it.request.url.toString() }
        } catch (e: Exception) {
            startUrl
        }
    }

    private fun fetchHtml(url: String, userAgent: String): String {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
            httpClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        } catch (e: Exception) {
            ""
        }
    }

}
