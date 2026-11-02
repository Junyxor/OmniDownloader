package com.omni.downloader.engine

import com.omni.downloader.data.model.FormatOption
import com.omni.downloader.data.model.GalleryImage
import com.omni.downloader.data.model.VideoMetadata
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses a Douyin aweme detail response (or a matching feed entry) into media metadata.
 * Network authentication/signing is deliberately outside this parser.
 * No third-party downloader, JS runtime or other APK dependency is added.
 */
internal object DouyinDetailParser {
    fun parse(body: String, pageUrl: String, expectedId: String): Result<VideoMetadata> = runCatching {
        val root = JSONObject(body)
        val status = root.optInt("status_code", 0)
        require(status == 0) { "抖音接口返回错误状态: $status" }
        val detail = root.optJSONObject("aweme_detail")
            ?: matchingFromFeed(root.optJSONArray("aweme_list"), expectedId)
            ?: root.takeIf { it.has("aweme_id") }
            ?: error("抖音接口未返回有效作品详情（可能需要登录或签名）")
        val actualId = detail.optString("aweme_id")
        require(actualId.isNotBlank() && (expectedId.isBlank() || actualId == expectedId)) {
            "抖音接口作品 ID 不匹配，已拒绝错误内容"
        }
        val title = detail.optString("desc").ifBlank { "抖音作品_$actualId" }
        val author = detail.optJSONObject("author")?.optString("nickname").orEmpty()
        val imagesJson = detail.optJSONArray("images")
        if (imagesJson != null && imagesJson.length() > 0) {
            val images = (0 until imagesJson.length()).map { i ->
                val image = imagesJson.optJSONObject(i) ?: error("图集第 ${i + 1} 项不是有效图片")
                val urls = extractUrls(image)
                require(urls.isNotEmpty()) { "图集第 ${i + 1} 张没有可用图片地址" }
                val liveVideo = image.optJSONObject("video")?.optJSONObject("play_addr")
                    ?.let(::extractUrls)?.firstOrNull()
                GalleryImage(
                    index = i,
                    url = urls.first(),
                    mirrors = urls.drop(1),
                    width = image.optInt("width", 0),
                    height = image.optInt("height", 0),
                    liveVideoUrl = liveVideo
                )
            }
            VideoMetadata(
                url = pageUrl,
                title = title,
                author = author,
                thumbnailUrl = images.first().url,
                siteName = "抖音",
                imageAssets = images
            )
        } else {
            val video = detail.optJSONObject("video") ?: error("作品没有可下载的视频或图集")
            val options = linkedMapOf<String, FormatOption>()
            val rates = video.optJSONArray("bit_rate")
            if (rates != null) {
                for (i in 0 until rates.length()) {
                    val rate = rates.optJSONObject(i) ?: continue
                    val playAddr = rate.optJSONObject("play_addr") ?: continue
                    val urls = extractUrls(playAddr)
                    if (urls.isEmpty()) continue
                    val width = playAddr.optInt("width", video.optInt("width", 0))
                    val height = playAddr.optInt("height", video.optInt("height", 0))
                    options[urls.first()] = FormatOption(
                        formatId = urls.first(),
                        resolutionLabel = if (height > 0) "${height}p" else "视频流",
                        width = width,
                        height = height,
                        ext = "mp4",
                        note = "平台播放资源（未验证水印状态）"
                    )
                }
            }
            val primary = video.optJSONObject("play_addr")?.let(::extractUrls).orEmpty()
            if (primary.isNotEmpty() && primary.first() !in options) {
                options[primary.first()] = FormatOption(
                    formatId = primary.first(),
                    resolutionLabel = "默认画质",
                    width = video.optInt("width", 0),
                    height = video.optInt("height", 0),
                    ext = "mp4",
                    note = "平台播放资源（未验证水印状态）"
                )
            }
            require(options.isNotEmpty()) { "作品视频没有可用播放地址" }
            val cover = video.optJSONObject("origin_cover")?.let(::extractUrls)?.firstOrNull()
                ?: video.optJSONObject("cover")?.let(::extractUrls)?.firstOrNull().orEmpty()
            VideoMetadata(
                url = pageUrl,
                title = title,
                author = author,
                durationText = video.optInt("duration", 0).takeIf { it > 0 }?.let { "${it / 1000}秒" }.orEmpty(),
                thumbnailUrl = cover,
                siteName = "抖音",
                availableVideoFormats = options.values.sortedWith(compareByDescending<FormatOption> { it.height }.thenByDescending { it.width })
            )
        }
    }

    private fun matchingFromFeed(entries: JSONArray?, id: String): JSONObject? {
        if (entries == null || id.isBlank()) return null
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (entry.optString("aweme_id") == id) return entry
        }
        return null
    }

    private fun extractUrls(data: JSONObject): List<String> {
        val list = data.optJSONArray("url_list")
            ?: data.optJSONObject("url")?.optJSONArray("url_list")
            ?: data.optJSONObject("image_url")?.optJSONArray("url_list")
        val candidates = if (list != null) (0 until list.length()).map { list.optString(it) }
                         else listOf(data.optString("url"))
        return candidates.map(String::trim).filter(::validMediaUrl).distinct()
    }

    private fun validMediaUrl(url: String): Boolean = try {
        val parsed = java.net.URI(url)
        (parsed.scheme == "https" || parsed.scheme == "http") && !parsed.host.isNullOrBlank() && parsed.userInfo == null
    } catch (_: Exception) { false }
}
