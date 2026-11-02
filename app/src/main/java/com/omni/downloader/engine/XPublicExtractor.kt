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
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Public X/Twitter status adapter using FxEmbed's documented API v2:
 * https://docs.fxembed.com/api/introduction/
 *
 * Sends only the public tweet status ID to a third-party public API. If this
 * service is unavailable, the caller may fall back to the local Lux extractor.
 */
object XPublicExtractor {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()
    private val imageIndex = Regex("""(?:^|[?&])omni_fx_image=(\d+)""")
    private val videoIndex = Regex("""(?:^|[?&])omni_fx_video=(\d+)""")

    internal fun statusId(url: String): String? =
        Regex("""/(?:status|statuses)/(\d{2,20})(?:[/?#]|$)""")
            .find(url)?.groupValues?.get(1)

    private fun items(media: JSONObject?, field: String): List<JSONObject> {
        val array = media?.optJSONArray(field) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    internal fun parse(json: String, originalUrl: String): VideoMetadata {
        val id = statusId(originalUrl) ?: throw IOException("不是 X/Twitter 公开帖子链接")
        val root = JSONObject(json)
        if (root.optInt("code", 0) != 200) throw IOException("X 公共帖子接口错误")
        val status = root.optJSONObject("status") ?: throw IOException("帖子不存在或无法公开访问")
        if (status.optString("id") != id || status.optString("type") == "tombstone")
            throw IOException("返回的帖子 ID 不匹配或作品已删除/私密")
        val author = status.optJSONObject("author")?.optString("name").orEmpty()
        val title = status.optString("text").trim().ifBlank { "X 帖子 $id" }.take(130)
        val media = status.optJSONObject("media")
        val videos = items(media, "videos")
        val photos = items(media, "photos")
        val base = originalUrl.substringBefore('#')

        val movieChildren = videos.mapIndexed { index, video ->
            val formats = items(video, "formats").filter {
                val container = it.optString("container")
                val url = it.optString("url")
                url.startsWith("https://") &&
                    container == "mp4" && !UrlSniffer.isM3u8Url(url)
            }.sortedByDescending { it.optInt("height") }.map { f ->
                val height = f.optInt("height")
                FormatOption(
                    formatId = f.optString("url"),
                    resolutionLabel = if (height > 0) height.toString() + "p MP4" else "原始 MP4",
                    ext = "mp4", note = "FxEmbed 公开帖子 API"
                )
            }.ifEmpty {
                val fallback = video.optString("url")
                if (fallback.startsWith("https://") && !UrlSniffer.isM3u8Url(fallback))
                    listOf(FormatOption(fallback, "原始 MP4", ext = "mp4"))
                else emptyList()
            }
            VideoMetadata(
                url = base + "#omni_fx_video=$index",
                title = if (videos.size > 1) "$title · 视频 " + (index + 1) else title,
                author = author, siteName = "X (Twitter)",
                thumbnailUrl = video.optString("thumbnail_url"),
                availableVideoFormats = formats, parserId = "fx"
            )
        }.filter { it.availableVideoFormats.isNotEmpty() }

        val imageChildren = photos.mapIndexedNotNull { index, photo ->
            val url = photo.optString("url")
            if (!url.startsWith("https://")) return@mapIndexedNotNull null
            VideoMetadata(
                url = base + "#omni_fx_image=$index",
                title = "$title · 图片 " + (index + 1),
                author = author, siteName = "图文",
                thumbnailUrl = url, parserId = "fx"
            )
        }

        val children = movieChildren + imageChildren
        if (children.isEmpty()) throw IOException("这个公开 X 帖子没有可直接下载的视频或图片")
        val first = children.first()
        return if (children.size == 1) first.copy(
            url = base, title = title, multiMediaList = emptyList()
        ) else VideoMetadata(
            url = base, title = title, author = author,
            siteName = if (movieChildren.isEmpty()) "图文" else "X (Twitter)",
            thumbnailUrl = first.thumbnailUrl, parserId = "fx",
            multiMediaList = children
        )
    }

    suspend fun extract(url: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val id = statusId(url) ?: throw IOException("帖子 URL 无法确定作品 ID")
            val req = Request.Builder().url("https://api.fxtwitter.com/2/status/$id")
                .header("User-Agent", "OmniDownloader/1.6.3 (public-status-media)")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("FxEmbed HTTP " + resp.code)
                parse(resp.body?.string().orEmpty(), url)
            }
        }
    }

    suspend fun download(
        context: Context, task: DownloadTask, staging: File, publicDir: File,
        onProgress: (Float, String, String, TaskStatus) -> Unit, proxy: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val latest = extract(task.url.substringBefore('#')).getOrThrow()
            val all = latest.multiMediaList.ifEmpty { listOf(latest) }
            val suffix = task.url.substringAfter('#', "")
            val image = imageIndex.find(suffix)?.groupValues?.get(1)?.toIntOrNull()
            val video = videoIndex.find(suffix)?.groupValues?.get(1)?.toIntOrNull()
            val chosen = when {
                image != null -> all.firstOrNull { it.url.endsWith("#omni_fx_image=$image") }
                video != null -> all.firstOrNull { it.url.endsWith("#omni_fx_video=$video") }
                else -> all.firstOrNull()
            } ?: throw IOException("所选 X 媒体已失效")
            if (chosen.siteName == "图文") {
                return@runCatching DownloadEngine.downloadCoverFile(
                    context, chosen.thumbnailUrl, task.title, proxy, publicDir, onProgress
                ).getOrThrow()
            }
            val format = chosen.availableVideoFormats.firstOrNull {
                it.resolutionLabel == task.selectedResolution
            } ?: chosen.availableVideoFormats.firstOrNull()
                ?: throw IOException("未找到可下载的 X 视频")
            DownloadEngine.downloadDirectMediaStream(
                context, task, format.formatId, proxy, staging, publicDir,
                onProgress, mapOf("Referer" to "https://x.com/")
            ).getOrThrow()
        }
    }
}
