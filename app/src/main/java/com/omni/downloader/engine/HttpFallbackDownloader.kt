package com.omni.downloader.engine

import android.content.Context
import android.os.SystemClock
import com.omni.downloader.data.model.TaskStatus
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * HTTP fallback with validated resume. Never reuse aria2's non-contiguous parts.
 * Range responses must match a saved ETag/Last-Modified and exact byte offset.
 */
internal object HttpFallbackDownloader {
    private val active = ConcurrentHashMap<String, Call>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val contentRange = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)

    fun cancel(taskId: String) {
        cancelled.add(taskId)
        active[taskId]?.cancel()
    }

    fun reset(taskId: String) { cancelled.remove(taskId) }

    private fun fingerprint(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class Resume(val key: String, val validator: String, val size: Long)

    private fun load(file: File): Resume? = runCatching {
        val lines = file.readLines()
        if (lines.size == 3) Resume(lines[0], lines[1], lines[2].toLong()) else null
    }.getOrNull()

    private fun save(file: File, r: Resume) {
        // Never put a signed URL, Cookie or authorization token into a sidecar.
        file.writeText("${r.key}\n${r.validator}\n${r.size}\n")
    }

    private fun client(proxy: String): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
        if (proxy.isNotBlank()) {
            val uri = URI(if ("://" in proxy) proxy else "http://$proxy")
            val host = uri.host ?: throw IOException("代理服务器地址无效")
            if (uri.port !in 1..65535) throw IOException("代理端口无效")
            val type = when (uri.scheme.lowercase()) {
                "http", "https" -> Proxy.Type.HTTP
                "socks", "socks5" -> Proxy.Type.SOCKS
                else -> throw IOException("不支持此代理协议")
            }
            builder.proxy(Proxy(type, InetSocketAddress(host, uri.port)))
        }
        return builder.build()
    }

    fun download(
        context: Context, taskId: String, url: String, destination: File,
        headers: Map<String, String>, proxyUrl: String,
        progress: (Float, String, String, TaskStatus) -> Unit
    ): Result<File> {
        val stateFile = File(destination.absolutePath + ".httpresume")
        try {
            val key = fingerprint(url)
            destination.parentFile?.mkdirs()
            val saved = load(stateFile)
            if (saved == null || saved.key != key || saved.validator.isBlank()) {
                destination.delete()
                stateFile.delete()
            }
            val client = client(proxyUrl)
            repeat(2) { attempt ->
                if (cancelled.contains(taskId)) throw IOException("下载已取消")
                val state = load(stateFile)
                val offset = if (state?.key == key && destination.isFile &&
                    destination.length() > 0 && state.validator.isNotBlank()) destination.length() else 0L
                val builder = Request.Builder().url(url)
                headers.forEach { (name, value) ->
                    if (name.matches(Regex("[A-Za-z0-9-]+")) &&
                        '\n' !in value && '\r' !in value) builder.header(name, value)
                }
                if (offset > 0) {
                    builder.header("Range", "bytes=$offset-")
                    builder.header("If-Range", state!!.validator)
                }
                val call = client.newCall(builder.build())
                if (active.putIfAbsent(taskId, call) != null) throw IOException("重复 HTTP 下载")
                try {
                    call.execute().use { response ->
                        if (cancelled.contains(taskId)) throw IOException("下载已取消")
                        if (offset > 0 && response.code == 416 && state!!.size == offset) {
                            stateFile.delete()
                            return Result.success(destination)
                        }
                        if (offset > 0 && response.code != 206) {
                            // A changed validator, or a server ignoring Range: restart from zero.
                            destination.delete()
                            stateFile.delete()
                            return@use
                        }
                        if (response.code !in 200..299) throw IOException("HTTP ${response.code}")
                        val mime = response.header("Content-Type").orEmpty().lowercase()
                        if (mime.startsWith("text/html") || mime.startsWith("application/json") ||
                            mime.startsWith("text/plain")) throw IOException("响应是网页/错误文本 ($mime)")
                        val body = response.body ?: throw IOException("下载响应无正文")
                        val expectedChunk = body.contentLength()
                        var total = if (expectedChunk > 0) offset + expectedChunk else -1L
                        if (response.code == 206) {
                            val match = contentRange.matchEntire(response.header("Content-Range").orEmpty())
                                ?: throw IOException("无效 Content-Range")
                            val start = match.groupValues[1].toLong()
                            if (start != offset) throw IOException("Range 起点不一致，拒绝拼接")
                            total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull() ?: -1L
                            if (state?.size ?: -1L > 0 && total > 0 && state!!.size != total) {
                                throw IOException("源文件长度变化，拒绝拼接")
                            }
                        }
                        val validator = response.header("ETag").orEmpty()
                            .takeUnless { it.startsWith("W/") || it.isBlank() }
                            ?: response.header("Last-Modified").orEmpty()
                        if (offset == 0L) {
                            if (validator.isNotBlank()) save(stateFile, Resume(key, validator, total))
                            else stateFile.delete()
                        }
                        var count = offset
                        val started = SystemClock.elapsedRealtime()
                        var tick = started
                        var tickBytes = 0L
                        RandomAccessFile(destination, "rw").use { output ->
                            if (offset == 0L) output.setLength(0)
                            output.seek(offset)
                            body.byteStream().use { source ->
                                val buf = ByteArray(65536)
                                while (true) {
                                    if (cancelled.contains(taskId)) throw IOException("下载已取消")
                                    val n = source.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    count += n
                                    tickBytes += n
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - tick >= 350L) {
                                        val bps = tickBytes * 1000L / (now - tick)
                                        val pct = if (total > 0) (count * 94f / total).coerceIn(1f, 94f) else 1f
                                        val eta = if (total > count && bps > 0) "${(total-count)/bps}s" else ""
                                        progress(pct, "$bps B/s", eta, TaskStatus.DOWNLOADING)
                                        tick = now
                                        tickBytes = 0
                                    }
                                }
                            }
                        }
                        if (expectedChunk >= 0 && count - offset != expectedChunk)
                            throw IOException("服务器提前断开：已收 ${count-offset}/$expectedChunk")
                        if (total > 0 && count != total) throw IOException("下载不完整：$count/$total")
                        if (count == 0L) throw IOException("文件为空")
                        stateFile.delete()
                        progress(95f, "", "HTTP 校验完成...", TaskStatus.PROCESSING)
                        return Result.success(destination)
                    }
                } finally {
                    active.remove(taskId, call)
                }
            }
            throw IOException("下载源不接受安全断点续传，完整下载重试失败")
        } catch (e: Exception) {
            return Result.failure(if (cancelled.contains(taskId)) IOException("下载已取消", e) else e)
        }
    }
}
