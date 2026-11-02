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
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Conservative site-independent HTML5/OG/JSON-LD media discovery.
 * No JS execution, DRM handling or third-party proxy service.
 */
object GenericWebVideoExtractor {
    private const val MAX_HTML = 2 * 1024 * 1024
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).followRedirects(true).build()

    internal data class MediaCandidate(val url: String, val priority: Int, val audioOnly: Boolean = false)

    private fun validUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.userInfo != null) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (!host.contains('.') || host.endsWith(".local") || host.endsWith(".internal") ||
            host in setOf("localhost", "127.0.0.1", "0.0.0.0")) return false
        return true
    }

    private fun add(
        items: MutableList<MediaCandidate>, seen: MutableSet<String>,
        url: String, priority: Int, audio: Boolean = false
    ) {
        val value = url.trim().replace("&amp;", "&")
        if (!validUrl(value) || UrlSniffer.isM3u8Url(value) ||
            URI(value).path.orEmpty().endsWith(".mpd", ignoreCase = true)) return
        if (seen.add(value)) items.add(MediaCandidate(value, priority, audio))
    }

    private fun ldJson(
        value: Any?, base: URI, items: MutableList<MediaCandidate>,
        seen: MutableSet<String>, depth: Int = 0
    ) {
        if (depth > 7) return
        when (value) {
            is JSONArray -> for (i in 0 until minOf(value.length(), 30)) {
                ldJson(value.opt(i), base, items, seen, depth + 1)
            }
            is JSONObject -> {
                val type = value.opt("@type")
                val video = when (type) {
                    is String -> type.endsWith("VideoObject", ignoreCase = true)
                    is JSONArray -> (0 until type.length()).any {
                        type.optString(it).endsWith("VideoObject", ignoreCase = true)
                    }
                    else -> false
                }
                if (video) {
                    when (val url = value.opt("contentUrl")) {
                        is String -> add(items, seen, base.resolve(url).toString(), 90)
                        is JSONArray -> for (i in 0 until minOf(url.length(), 10)) {
                            add(items, seen, base.resolve(url.optString(i)).toString(), 90)
                        }
                    }
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "contentUrl") continue
                    when (val child = value.opt(key)) {
                        is JSONObject, is JSONArray -> ldJson(child, base, items, seen, depth + 1)
                    }
                }
            }
        }
    }

    /** Pure HTML parser, suitable for offline JVM regression tests. */
    internal fun candidates(html: String, pageUrl: String): List<MediaCandidate> {
        val doc = Jsoup.parse(html, pageUrl)
        val base = URI(pageUrl)
        val items = mutableListOf<MediaCandidate>()
        val seen = mutableSetOf<String>()
        doc.select("meta[property], meta[name]").forEach { tag ->
            val key = tag.attr("property").ifBlank { tag.attr("name") }.lowercase(Locale.ROOT)
            if (key in setOf("og:video", "og:video:url", "og:video:secure_url",
                    "twitter:player:stream", "og:audio", "og:audio:secure_url")) {
                val url = runCatching { base.resolve(tag.attr("content")).toString() }.getOrDefault("")
                add(items, seen, url, 100, key.startsWith("og:audio"))
            }
        }
        doc.select("script[type=application/ld+json]").take(20).forEach { script ->
            val json = runCatching { JSONTokener(script.data()).nextValue() }.getOrNull()
            ldJson(json, base, items, seen)
        }
        doc.select("video[src], video[data-src], video source[src], video source[data-src]")
            .take(30).forEach { tag ->
                add(items, seen, tag.absUrl("src").ifBlank { tag.absUrl("data-src") }, 60)
            }
        doc.select("audio[src], audio source[src]").take(10).forEach { tag ->
            add(items, seen, tag.absUrl("src"), 40, true)
        }
        return items.sortedByDescending { it.priority }.take(20)
    }

    private fun format(media: MediaCandidate): FormatOption {
        val ext = URI(media.url).path.orEmpty().substringAfterLast('.', "")
            .lowercase(Locale.ROOT).takeIf {
                it in setOf("mp4", "webm", "m4a", "mp3", "ogg", "mov")
            } ?: if (media.audioOnly) "m4a" else "mp4"
        return FormatOption(
            formatId = media.url,
            resolutionLabel = if (media.audioOnly) "网页原音频" else "网页原视频",
            ext = ext, isAudioOnly = media.audioOnly, note = "HTML5 / OpenGraph / JSON-LD"
        )
    }

    internal fun parseHtml(html: String, url: String, index: Int = 0): VideoMetadata? {
        val media = candidates(html, url)
        if (media.isEmpty()) return null
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() } ?: doc.title().ifBlank { "网页视频" }
        val cover = doc.selectFirst("meta[property=og:image]")?.absUrl("content").orEmpty()
        val author = doc.selectFirst("meta[name=author]")?.attr("content").orEmpty()
        val page = url.substringBefore('#')
        val children = media.mapIndexed { i, entry ->
            VideoMetadata(
                url = "$page#omni_vod=$i",
                title = if (media.size > 1) "$title · 媒体 ${i + 1}" else title,
                author = author, thumbnailUrl = cover, siteName = "通用网页",
                availableVideoFormats = if (entry.audioOnly) emptyList() else listOf(format(entry)),
                availableAudioFormats = if (entry.audioOnly) listOf(format(entry)) else emptyList()
            )
        }
        return children.getOrElse(index) { children.first() }.copy(
            url = if (index > 0) "$page#omni_vod=$index" else page,
            multiMediaList = if (children.size > 1) children else emptyList()
        )
    }

    suspend fun extract(input: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = URI(input)
            if (!validUrl(input.substringBefore('#'))) throw IOException("只接受有效的 HTTPS 网页链接")
            val index = Regex("""(?:^|[?&])omni_vod=(\d+)""")
                .find(uri.fragment.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 0..19 } ?: 0
            val request = Request.Builder().url(input.substringBefore('#'))
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("网页 HTTP ${response.code}")
                val type = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                if (type.isNotBlank() && !type.contains("text/html") &&
                    !type.contains("application/xhtml+xml")) throw IOException("目标不是 HTML 网页")
                val body = response.body ?: throw IOException("网页响应为空")
                if (body.contentLength() > MAX_HTML) throw IOException("网页超过 2MB 限制")
                val bytes = ByteArrayOutputStream()
                val reader = body.byteStream()
                val buf = ByteArray(16384)
                while (bytes.size() < MAX_HTML) {
                    val n = reader.read(buf, 0, minOf(buf.size, MAX_HTML - bytes.size()))
                    if (n == -1) break
                    bytes.write(buf, 0, n)
                }
                val html = bytes.toString(body.contentType()?.charset(Charsets.UTF_8)?.name() ?: "UTF-8")
                parseHtml(html, response.request.url.toString(), index)
                    ?: throw IOException("没找到网页直接公开的媒体 URL；该网站可能需要 JavaScript、专用解析器或 DRM。")
            }
        }
    }

    suspend fun download(
        context: Context, task: DownloadTask, stagingDir: File, publicDir: File,
        onProgress: (Float, String, String, TaskStatus) -> Unit, proxyUrl: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val metadata = extract(task.url).getOrElse { return@withContext Result.failure(it) }
        val formats = metadata.availableVideoFormats + metadata.availableAudioFormats
        val url = formats.firstOrNull { it.resolutionLabel == task.selectedResolution }?.formatId
            ?: formats.firstOrNull()?.formatId
            ?: return@withContext Result.failure(IOException("通用网页未返回可下载的媒体流"))
        DownloadEngine.downloadDirectMediaStream(
            context, task, url, proxyUrl, stagingDir, publicDir, onProgress,
            mapOf("Referer" to task.url.substringBefore('#'))
        )
    }
}
