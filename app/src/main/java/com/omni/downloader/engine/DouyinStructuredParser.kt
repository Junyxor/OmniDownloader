package com.omni.downloader.engine

import com.omni.downloader.data.model.FormatOption
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

    private fun images(item: JSONObject): List<String> {
        val photoInfo = item.optJSONObject("image_post_info")
            ?: item.optJSONObject("imagePostInfo")
        val list = photoInfo?.optJSONArray("images")
            ?: item.optJSONArray("images")
            ?: item.optJSONArray("image_list")
            ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until minOf(list.length(), 100)) {
            val row = list.optJSONObject(i) ?: continue
            val link = urlList(row.optJSONObject("display_image")).firstOrNull()
                ?: urlList(row.optJSONObject("image_url")).firstOrNull()
                ?: urlList(row.optJSONObject("url")).firstOrNull()
                ?: urlList(row).firstOrNull()
                ?: row.optJSONArray("url_list")?.optString(0).orEmpty()
            if (link.startsWith("http")) result.add(link)
        }
        return result.distinct()
    }

    fun parse(json: String, expectedId: String, source: String): VideoMetadata? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val item = find(root, expectedId) ?: return null
        val title = item.optString("desc").ifBlank {
            item.optString("title").ifBlank { "抖音作品_$expectedId" }
        }.trim()
        val author = item.optJSONObject("author")?.optString("nickname").orEmpty()
        val pictures = images(item)
        val isNote = source.contains("/note/") || source.contains("/slide") ||
            item.optInt("aweme_type", -1) == 68 ||
            item.optJSONObject("image_post_info") != null
        if (pictures.isNotEmpty() && isNote) {
            val children = pictures.mapIndexed { index, link ->
                VideoMetadata(
                    url = source.substringBefore('#') + "#omni_douyin_image=$index",
                    title = "$title · 图片 " + (index + 1),
                    author = author,
                    thumbnailUrl = link,
                    siteName = "图文",
                    parserId = "douyin"
                )
            }
            return VideoMetadata(
                url = source.substringBefore('#'), title = title, author = author,
                siteName = "图文", thumbnailUrl = pictures.first(),
                multiMediaList = children, parserId = "douyin"
            )
        }

        val video = item.optJSONObject("video") ?: return null
        val cover = urlList(video.optJSONObject("cover")).firstOrNull().orEmpty()
        val options = mutableListOf<String>()
        val bitrate = video.optJSONArray("bit_rate")
        if (bitrate != null) for (i in 0 until bitrate.length()) {
            options += urlList(bitrate.optJSONObject(i)?.optJSONObject("play_addr"))
        }
        options += urlList(video.optJSONObject("play_addr_h264"))
        options += urlList(video.optJSONObject("play_addr"))
        val direct = options.firstOrNull()?.replace("playwm", "play") ?: return null
        val duration = item.optInt("duration") / 1000
        return VideoMetadata(
            url = source.substringBefore('#'), title = title, author = author,
            durationText = if (duration > 0) duration.toString() + "秒" else "",
            thumbnailUrl = cover, siteName = "抖音",
            availableVideoFormats = listOf(FormatOption(
                formatId = direct, resolutionLabel = "原画（无水印）",
                ext = "mp4", note = "作品 ID 匹配 · 原生下载"
            )),
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
