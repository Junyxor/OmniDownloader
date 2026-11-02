package com.omni.downloader.engine

import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.GalleryImage
import com.omni.downloader.data.model.VideoMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Strict, identity-checked Douyin detail parser.
 * Handles mobile Feed and nested mobile share-page SSR structures.
 * Does not blindly read a recommendation or unrelated video from page HTML.
 */
object DouyinStructuredParser {
    private val markers = listOf(
        "window._ROUTER_DATA", "window._SSR_DATA",
        "window.__INIT_PROPS__", "__UNIVERSAL_DATA_FOR_REHYDRATION__",
        "window.__NEXT_DATA__"
    )

    private fun identity(item: JSONObject): String {
        return item.optString("aweme_id").ifBlank {
            item.optString("awemeId").ifBlank { item.optString("id") }
        }
    }

    private fun find(item: Any?, id: String, depth: Int = 0): JSONObject? {
        if (depth > 10) return null
        return when (item) {
            is JSONArray -> {
                (0 until minOf(item.length(), 100)).firstNotNullOfOrNull {
                    find(item.opt(it), id, depth + 1)
                }
            }
            is JSONObject -> {
                if (identity(item) == id &&
                    (item.optJSONObject("video") != null ||
                     item.optJSONArray("images") != null ||
                     item.optJSONObject("image_post_info") != null ||
                     item.optString("desc").isNotBlank())) return item
                // Restrict recursive traversal to nested data structures, checking every ID.
                val keys = item.keys()
                var hit: JSONObject? = null
                while (keys.hasNext() && hit == null) {
                    val key = keys.next()
                    val child = item.opt(key)
                    if (child is JSONObject || child is JSONArray) hit = find(child, id, depth + 1)
                }
                hit
            }
            else -> null
        }
    }

    private fun urlList(obj: JSONObject?): List<String> {
        if (obj == null) return emptyList()
        val value = obj.optJSONArray("url_list") ?: obj.optJSONArray("urlList")
        if (value != null) {
            return (0 until value.length()).map { value.optString(it) }
                .filter { it.startsWith("https://") || it.startsWith("http://") }
        }
        val str = obj.optString("url")
        return if (str.startsWith("https://") || str.startsWith("http://")) listOf(str)
            else emptyList()
    }

    // Retain exact gallery order (including intentional duplicate pictures).
    // One invalid row invalidates the album rather than silently skipping an image.
    private fun images(item: JSONObject): List<GalleryImage>? {
        val photoInfo = item.optJSONObject("image_post_info")
            ?: item.optJSONObject("imagePostInfo")
        val list = photoInfo?.optJSONArray("images")
            ?: item.optJSONArray("images")
            ?: item.optJSONArray("image_list")
            ?: return emptyList()
        if (list.length() == 0 || list.length() > 100) return null
        return (0 until list.length()).map { i ->
            val row = list.optJSONObject(i) ?: return null
            val primary = row.optJSONObject("display_image")
                ?: row.optJSONObject("image_url")
                ?: row.optJSONObject("url")
                ?: row
            val urls = urlList(primary).ifEmpty { urlList(row) }.distinct()
            if (urls.isEmpty()) return null
            val clip = row.optJSONObject("video")?.optJSONObject("play_addr")
                ?.let(::urlList)?.firstOrNull()
            GalleryImage(
                index = i, url = urls.first(), mirrors = urls.drop(1),
                width = row.optInt("width", primary.optInt("width", 0)),
                height = row.optInt("height", primary.optInt("height", 0)),
                liveVideoUrl = clip
            )
        }
    }

    fun parse(json: String, expectedId: String, source: String): VideoMetadata? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val item = find(root, expectedId) ?: return null
        val title = item.optString("desc").ifBlank {
            item.optString("title").ifBlank { "抖音作品_$expectedId" }
        }.trim()
        val author = item.optJSONObject("author")?.optString("nickname").orEmpty()
        val pictures = images(item) ?: return null
        val isNote = source.contains("/note/") || source.contains("/slide") ||
            item.optInt("aweme_type", -1) == 68 ||
            item.optJSONObject("image_post_info") != null
        if (pictures.isNotEmpty() && isNote) {
            val children = pictures.map { picture ->
                VideoMetadata(
                    url = source.substringBefore('#') + "#omni_douyin_image=${picture.index}",
                    title = "$title · 图片 " + (picture.index + 1),
                    author = author,
                    thumbnailUrl = picture.url,
                    siteName = "图文",
                    parserId = "douyin",
                    imageAssets = listOf(picture)
                )
            }
            return VideoMetadata(
                url = source.substringBefore('#'), title = title, author = author,
                siteName = "图文", thumbnailUrl = pictures.first().url,
                multiMediaList = children, parserId = "douyin", imageAssets = pictures
            )
        }

        val video = item.optJSONObject("video") ?: return null
        val cover = urlList(video.optJSONObject("origin_cover")).firstOrNull()
            ?: urlList(video.optJSONObject("cover")).firstOrNull().orEmpty()
        val formats = linkedMapOf<String, FormatOption>()
        fun addFormats(address: JSONObject?) {
            if (address == null) return
            val width = address.optInt("width", video.optInt("width", 0))
            val height = address.optInt("height", video.optInt("height", 0))
            for (url in urlList(address)) {
                if (url !in formats) {
                    formats[url] = FormatOption(
                        formatId = url,
                        resolutionLabel = if (height > 0) "${height}p" else "默认画质",
                        width = width,
                        height = height,
                        ext = "mp4",
                        note = "原始播放地址，未验证是否无水印"
                    )
                }
            }
        }
        val bitrate = video.optJSONArray("bit_rate")
        if (bitrate != null) for (i in 0 until bitrate.length()) {
            addFormats(bitrate.optJSONObject(i)?.optJSONObject("play_addr"))
        }
        addFormats(video.optJSONObject("play_addr_h264"))
        addFormats(video.optJSONObject("play_addr"))
        if (formats.isEmpty()) return null
        val durationMs = video.optInt("duration", item.optInt("duration", 0))
        return VideoMetadata(
            url = source.substringBefore('#'), title = title, author = author,
            durationText = if (durationMs > 0) (durationMs / 1000).toString() + "秒" else "",
            thumbnailUrl = cover, siteName = "抖音",
            availableVideoFormats = formats.values.sortedWith(
                compareByDescending<FormatOption> { it.height }.thenByDescending { it.width }
            ),
            parserId = "douyin"
        )
    }

    private fun balancedJson(content: String, marker: String): String? {
        val at = content.indexOf(marker)
        if (at < 0) return null
        val begin = content.indexOf('{', at + marker.length)
        if (begin < 0) return null
        var level = 0
        var inQuotes = false
        var escape = false
        for (i in begin until content.length) {
            val c = content[i]
            if (inQuotes) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inQuotes = false
            } else {
                if (c == '"') inQuotes = true
                if (c == '{') level++
                else if (c == '}') {
                    level--
                    if (level == 0) return content.substring(begin, i + 1)
                }
            }
        }
        return null
    }

    fun parseHtml(html: String, id: String, source: String): VideoMetadata? {
        for (mark in markers) {
            val nested = balancedJson(html, mark) ?: continue
            parse(nested, id, source)?.let { return it }
        }
        return null
    }
}
