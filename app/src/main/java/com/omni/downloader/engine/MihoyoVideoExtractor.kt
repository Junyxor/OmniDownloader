package com.omni.downloader.engine

import android.content.Context
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Native miHoYo Wiki / HoYoLAB CN community video extraction.
 * Uses public read-only APIs: no WebView, Python, credentials or DS signing.
 */
object MihoyoVideoExtractor {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private val wikiAppNames = mapOf(
        "bh3" to "bh3_wiki",
        "sr" to "sr_wiki",
        "zzz" to "zzz_wiki",
        "ys" to "ys_obc",
        "wd" to "wd_obc"
    )
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    internal enum class SourceType { WIKI, POST }
    internal data class Source(
        val type: SourceType,
        val id: String,
        val appSn: String = "",
        val videoIndex: Int = 0
    )
    private data class VideoGroup(
        val title: String,
        val thumbnail: String,
        val formats: List<FormatOption>,
        val duration: String = ""
    )
    internal data class HtmlVideo(val url: String, val poster: String = "")

    /** Host and path must both match; never treat attacker-controlled substrings as miHoYo. */
    internal fun parseSource(rawUrl: String): Source? {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http")) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val index = Regex("""(?:^|&)omni_vod=(\d+)""")
            .find(uri.fragment.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 0..99 } ?: 0
        if (host == "baike.mihoyo.com" || host == "bbs.mihoyo.com") {
            val wiki = Regex("""^/(bh3|sr|zzz|ys|wd)/(?:wiki|obc)/content/(\d+)(?:/|$)""").find(path)
            if (wiki != null) {
                val app = wikiAppNames[wiki.groupValues[1]] ?: return null
                return Source(SourceType.WIKI, wiki.groupValues[2], app, index)
            }
        }
        if (host in setOf("www.miyoushe.com", "miyoushe.com", "bbs.mihoyo.com")) {
            val post = Regex("""^/(?:bh3|ys|sr|zzz|wd|bh2|dby)/article/(\d+)(?:/|$)""").find(path)
            if (post != null) return Source(SourceType.POST, post.groupValues[1], videoIndex = index)
        }
        return null
    }

    fun isSupported(url: String): Boolean = parseSource(url) != null

    suspend fun extract(rawUrl: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val source = parseSource(rawUrl)
                ?: throw IllegalArgumentException("不是受支持的米哈游百科或米游社文章链接")
            when (source.type) {
                SourceType.WIKI -> extractWiki(rawUrl, source)
                SourceType.POST -> extractPost(rawUrl, source)
            }
        }
    }

    private fun extractWiki(rawUrl: String, source: Source): VideoMetadata {
        val api = "https://api-static.mihoyo.com/common/blackboard/${source.appSn}" +
            "/v1/content/info?content_id=${source.id}&app_sn=${source.appSn}"
        val data = requireSuccess(getJson(api, "https://baike.mihoyo.com/"))
        val content = data.optJSONObject("content")
            ?: throw IOException("百科接口未返回词条正文")
        val title = content.optString("title").ifBlank { "米哈游百科_${source.id}" }
        val icon = content.optString("icon")
        val author = content.optString("author_name")
        val groups = mutableListOf<VideoGroup>()
        val unique = mutableSetOf<String>()
        val tabs = content.optJSONArray("contents")
        if (tabs != null) for (i in 0 until tabs.length()) {
            val html = tabs.optJSONObject(i)?.optString("text").orEmpty()
            for (video in extractHtmlVideos(html)) {
                if (!unique.add(video.url)) continue
                // Wiki articles may retain deleted MP4 links: validate each candidate cheaply.
                if (!isLiveVideo(video.url, "https://baike.mihoyo.com/")) continue
                groups += VideoGroup(
                    title = title,
                    thumbnail = video.poster.ifBlank { icon },
                    formats = listOf(
                        FormatOption(
                            formatId = video.url,
                            resolutionLabel = "原视频",
                            ext = formatExtension(video.url),
                            note = "百科原始视频"
                        )
                    )
                )
                if (groups.size >= 30) break
            }
            if (groups.size >= 30) break
        }
        if (groups.isEmpty()) throw IOException("词条正文没有可用的视频（可能是图片词条或视频已失效）")
        return assemble(rawUrl, source.videoIndex, "米哈游百科", title, author, icon, groups)
    }

    private fun extractPost(rawUrl: String, source: Source): VideoMetadata {
        val data = fetchPost(source.id)
        val wrapper = data.optJSONObject("post") ?: throw IOException("米游社未返回帖子数据")
        val post = wrapper.optJSONObject("post") ?: throw IOException("帖子内容为空")
        val title = post.optString("subject").ifBlank { "米游社视频_${source.id}" }
        val author = wrapper.optJSONObject("user")?.optString("nickname").orEmpty()
        val cover = post.optString("cover")
        val groups = mutableListOf<VideoGroup>()
        val vodList = wrapper.optJSONArray("vod_list")
        if (vodList != null) for (i in 0 until vodList.length()) {
            val vod = vodList.optJSONObject(i) ?: continue
            var variants = vod.optJSONArray("resolutions")
            if (variants == null || variants.length() == 0) variants = vod.optJSONArray("backup_resolutions")
            val formats = mutableListOf<FormatOption>()
            if (variants != null) for (j in 0 until variants.length()) {
                val variant = variants.optJSONObject(j) ?: continue
                val url = variant.optString("url")
                if (!isTrustedMediaUrl(url)) continue
                val height = variant.optInt("height")
                val label = variant.optString("definition").ifBlank {
                    if (height > 0) "${height}P" else "原视频"
                }
                val sizeBytes = variant.optString("size").toLongOrNull() ?: 0L
                val note = if (sizeBytes > 0) "%.1f MB".format(Locale.US, sizeBytes / 1048576.0) else ""
                formats += FormatOption(
                    formatId = url,
                    resolutionLabel = label,
                    width = variant.optInt("width"),
                    height = height,
                    ext = formatExtension(url),
                    approximateSize = note,
                    note = "米游社视频源"
                )
            }
            if (formats.isNotEmpty()) {
                val durationSeconds = vod.optLong("duration") / 1000
                groups += VideoGroup(
                    title = title,
                    thumbnail = vod.optString("cover").ifBlank { cover },
                    formats = formats.sortedByDescending { it.height }.distinctBy { it.resolutionLabel },
                    duration = if (durationSeconds > 0) "${durationSeconds}秒" else ""
                )
            }
        }
        // Some legacy posts embed plain MP4 links without vod_list.
        if (groups.isEmpty()) {
            val html = post.optString("content")
            val urls = Regex("""https?://[^\s"'<>]+?\.mp4(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
                .findAll(html).map { decodeHtmlEntities(it.value) }
                .filter(::isTrustedMediaUrl).distinct().take(20).toList()
            urls.forEach { url ->
                groups += VideoGroup(
                    title = title, thumbnail = cover,
                    formats = listOf(FormatOption(url, "原视频", ext = "mp4"))
                )
            }
        }
        if (groups.isEmpty()) throw IOException("帖子中没有可下载的视频；图片和外站播放器暂不作为视频直链")
        return assemble(rawUrl, source.videoIndex, "米游社", title, author, cover, groups)
    }

    private fun assemble(
        rawUrl: String,
        selectedIndex: Int,
        site: String,
        title: String,
        author: String,
        cover: String,
        groups: List<VideoGroup>
    ): VideoMetadata {
        val pageUrl = rawUrl.substringBefore('#')
        val children = groups.mapIndexed { index, video ->
            VideoMetadata(
                url = "$pageUrl#omni_vod=$index",
                title = if (groups.size > 1) "$title - 视频 ${index + 1}" else title,
                author = author,
                durationText = video.duration,
                thumbnailUrl = video.thumbnail.ifBlank { cover },
                siteName = site,
                availableVideoFormats = video.formats
            )
        }
        val active = children.getOrElse(selectedIndex) { children.first() }
        return active.copy(
            url = rawUrl,
            multiMediaList = if (children.size > 1) children else emptyList()
        )
    }

    private fun getJson(url: String, referer: String): JSONObject {
        var error: Exception? = null
        repeat(3) {
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", referer)
                    .header("Accept", "application/json")
                    .header("x-rpc-client_type", "2")
                    .header("x-rpc-app_version", "2.71.1")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("接口 HTTP ${response.code}")
                    val body = response.body?.string() ?: throw IOException("接口响应体为空")
                    return JSONObject(body)
                }
            } catch (e: Exception) {
                error = e
            }
        }
        throw IOException("米哈游公开接口访问失败：${error?.message}", error)
    }

    private fun requireSuccess(root: JSONObject): JSONObject {
        val code = root.optInt("retcode", -1)
        if (code != 0) throw IOException("米哈游 API 返回错误 $code：${root.optString("message")}")
        return root.optJSONObject("data") ?: throw IOException("API data 为空")
    }

    private fun fetchPost(id: String): JSONObject {
        var error: Exception? = null
        for (host in listOf("bbs-api.miyoushe.com", "bbs-api-static.miyoushe.com")) {
            try {
                val url = "https://$host/post/wapi/getPostFull?post_id=$id"
                return requireSuccess(getJson(url, "https://www.miyoushe.com/"))
            } catch (e: Exception) {
                error = e
            }
        }
        throw IOException("米游社帖子解析失败：${error?.message}", error)
    }

    private val videoTag = Regex("""<(?:video|source)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attribute = Regex("""([:\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

    /** Extract both video[src] and nested source[src], supporting single quotes and HTML entities. */
    internal fun extractHtmlVideos(html: String): List<HtmlVideo> =
        videoTag.findAll(html).mapNotNull { tag ->
            val fields = attribute.findAll(tag.value).associate { match ->
                match.groupValues[1].lowercase(Locale.ROOT) to
                    decodeHtmlEntities(match.groupValues[2].ifEmpty { match.groupValues[3] })
            }
            val src = fields["src"].orEmpty()
            if (!isTrustedMediaUrl(src)) null
            else HtmlVideo(src, fields["poster"].orEmpty())
        }.distinctBy { it.url }.toList()

    private fun decodeHtmlEntities(text: String): String = text
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&#38;", "&")
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'")

    private fun isTrustedMediaUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host == "mihoyo.com" || host.endsWith(".mihoyo.com") ||
            host == "miyoushe.com" || host.endsWith(".miyoushe.com")
    }

    private fun isLiveVideo(url: String, referer: String): Boolean {
        val request = Request.Builder().url(url)
            .head().header("User-Agent", USER_AGENT).header("Referer", referer).build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) return true
                if (response.code != 405 && response.code != 403) return false
            }
            val rangeRequest = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT).header("Referer", referer)
                .header("Range", "bytes=0-0").build()
            client.newCall(rangeRequest).execute().use { response ->
                return response.isSuccessful
            }
        } catch (_: Exception) {
            // Do not advertise a link that cannot even be reached.
            return false
        }
    }

    private fun formatExtension(url: String): String =
        if (URI(url).path.orEmpty().endsWith(".m3u8", ignoreCase = true)) "m3u8" else "mp4"

    /** Signed community VOD links must be refreshed when queued for a long time. */
    internal fun isExpiring(url: String, nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        val expiration = Regex("""(?:[?&])auth_key=(\d+)-""")
            .find(url)?.groupValues?.get(1)?.toLongOrNull() ?: return false
        return expiration <= nowSeconds + 120
    }

    private fun selectMediaUrl(metadata: VideoMetadata, resolution: String): String {
        val formats = metadata.availableVideoFormats
        return formats.firstOrNull { it.resolutionLabel == resolution }?.formatId
            ?: formats.firstOrNull()?.formatId.orEmpty()
    }

    /**
     * Reuse the existing direct download pipeline including resume, cancellation,
     * media signature checks, MP3/FLAC conversion and GIF export.
     */
    suspend fun download(
        context: Context,
        task: DownloadTask,
        stagingDir: File,
        publicDir: File,
        onProgressUpdate: (Float, String, String, TaskStatus) -> Unit,
        proxyUrl: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val source = parseSource(task.url)
            ?: return@withContext Result.failure(IOException("不支持的米哈游资源地址"))
        val site = if (source.type == SourceType.WIKI) "米哈游百科" else "米游社"
        val headers = DomesticMediaSource.headers(site) + task.directMediaHeaders

        suspend fun refresh(): Result<String> = extract(task.url).map { metadata ->
            selectMediaUrl(metadata, task.selectedResolution)
                .takeIf { it.startsWith("https://") }
                ?: throw IOException("重新解析后没有找到选定的视频源")
        }

        var url = task.directMediaUrl
        if (url.isBlank() || (source.type == SourceType.POST && isExpiring(url))) {
            url = refresh().getOrElse { return@withContext Result.failure(it) }
        }
        if (!isTrustedMediaUrl(url)) {
            return@withContext Result.failure(IOException("接口返回了不可信的媒体地址"))
        }
        val first = DownloadEngine.downloadDirectMediaStream(
            context, task, url, proxyUrl, stagingDir, publicDir, onProgressUpdate, headers
        )
        if (first.isSuccess || source.type != SourceType.POST) return@withContext first
        // Cancellation is intentional: do not restart the task to refresh a URL.
        if (first.exceptionOrNull()?.message.orEmpty().contains("取消")) return@withContext first

        // May be a 403 from a stale auth_key, or a link invalidated before its stated TTL.
        val freshUrl = refresh().getOrElse { return@withContext Result.failure(it) }
        if (!isTrustedMediaUrl(freshUrl)) return@withContext first
        DownloadEngine.downloadDirectMediaStream(
            context, task, freshUrl, proxyUrl, stagingDir, publicDir, onProgressUpdate, headers
        )
    }
}
