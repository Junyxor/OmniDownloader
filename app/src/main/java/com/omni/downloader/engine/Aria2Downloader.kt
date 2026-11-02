package com.omni.downloader.engine

import android.content.Context
import android.util.Log
import com.omni.downloader.data.model.TaskStatus
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Real Android ARM64 aria2c process, no Python, WebView, shell, RPC server or embedded browser.
 * Each task has its own process and aria2 control file so failed transfers can be resumed.
 */
object Aria2Downloader {
    private const val TAG = "Aria2Downloader"
    private val processes = ConcurrentHashMap<String, Process>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val percent = Regex("""\((\d{1,3})%\)""")
    private val speed = Regex("""DL:([^\s\]]+)""")
    private val eta = Regex("""ETA:([^\s\]]+)""")

    fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libaria2c.so")

    fun isAvailable(context: Context): Boolean =
        binary(context).let { it.exists() && it.canExecute() }

    fun resetCancellation(taskId: String) {
        cancelled.remove(taskId)
    }

    fun cancel(taskId: String) {
        cancelled.add(taskId)
        processes[taskId]?.destroyForcibly()
    }

    /** Blocking operation: callers must dispatch to IO. */
    fun download(
        context: Context,
        taskId: String,
        url: String,
        destination: File,
        headers: Map<String, String> = emptyMap(),
        proxyUrl: String = "",
        onProgress: (Float, String, String, TaskStatus) -> Unit = { _, _, _, _ -> }
    ): Result<File> {
        var process: Process? = null
        return try {
            if (cancelled.contains(taskId)) throw IOException("任务已取消")
            val binary = binary(context)
            if (!binary.exists() || !binary.canExecute()) {
                throw IOException("原生 aria2 下载引擎缺失或无法执行（请安装 ARM64 版本）")
            }
            require(url.startsWith("https://", true) || url.startsWith("http://", true)) {
                "仅支持 HTTP/HTTPS 媒体下载"
            }
            if (proxyUrl.startsWith("socks", true)) {
                throw IOException("当前精简版 aria2 仅支持 HTTP(S) 代理；请在网络设置中使用 HTTP 代理")
            }
            destination.parentFile?.mkdirs()
            val args = mutableListOf(
                binary.absolutePath, "--no-conf=true", "--enable-color=false",
                "--console-log-level=warn", "--summary-interval=1",
                "--show-console-readout=true", "--file-allocation=none",
                "--continue=true", "--auto-file-renaming=false", "--allow-overwrite=true",
                "--max-concurrent-downloads=1", "--max-connection-per-server=8",
                "--split=8", "--min-split-size=1M", "--max-tries=5",
                "--retry-wait=2", "--connect-timeout=20", "--timeout=60",
                "--check-certificate=true",
                "--dir=${destination.parentFile!!.absolutePath}", "--out=${destination.name}"
            )
            val normalized = headers.entries.associate { it.key.lowercase() to it.value }
            args += "--user-agent=${normalized["user-agent"] ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile"}"
            normalized["referer"]?.let { args += "--referer=$it" }
            headers.forEach { (name, value) ->
                // Separate args avoid shell interpolation and command injection.
                if (name.lowercase() !in setOf("referer", "user-agent") &&
                    !name.contains('\r') && !name.contains('\n') &&
                    !value.contains('\r') && !value.contains('\n')) {
                    args += "--header=$name: $value"
                }
            }
            if (proxyUrl.isNotBlank()) args += "--all-proxy=$proxyUrl"
            args += url

            onProgress(1f, "", "aria2 八连接分段下载...", TaskStatus.DOWNLOADING)
            val cmd = ProcessBuilder(args).redirectErrorStream(true)
            process = cmd.start()
            if (cancelled.contains(taskId)) process.destroyForcibly()
            val previous = processes.putIfAbsent(taskId, process)
            if (previous != null) {
                process.destroyForcibly()
                throw IOException("此下载任务已在执行")
            }
            val log = StringBuilder(512)
            val started = System.nanoTime()
            var lastUpdate = 0L
            val reader = Thread({
                try {
                    process!!.inputStream.bufferedReader().use { input ->
                        val line = StringBuilder(512)
                        while (true) {
                            val ch = input.read()
                            if (ch == -1) break
                            if (ch == '\r'.code || ch == '\n'.code) {
                                val entry = line.toString()
                                line.setLength(0)
                                if (entry.isNotEmpty()) {
                                    if (log.length > 2200) log.delete(0, log.length - 1100)
                                    log.append(entry.take(160)).append('\n')
                                    val p = percent.find(entry)?.groupValues?.get(1)?.toFloatOrNull()
                                    if (p != null) {
                                        val now = System.nanoTime()
                                        if (now - lastUpdate > 250_000_000L) {
                                            lastUpdate = now
                                            onProgress(
                                                (p * 0.94f).coerceIn(1f, 94f),
                                                speed.find(entry)?.groupValues?.get(1).orEmpty(),
                                                eta.find(entry)?.groupValues?.get(1).orEmpty(),
                                                TaskStatus.DOWNLOADING
                                            )
                                        }
                                    }
                                }
                            } else if (line.length < 512) line.append(ch.toChar())
                        }
                    }
                } catch (ignored: Exception) { }
            }, "aria2-output-$taskId")
            reader.isDaemon = true
            reader.start()
            val exitCode = process.waitFor()
            reader.join(1500)
            if (cancelled.contains(taskId)) throw IOException("任务已取消")
            if (exitCode != 0 || !destination.isFile || destination.length() == 0L) {
                throw IOException("aria2 下载失败 (exit=$exitCode): ${log.toString().takeLast(400)}")
            }
            // Sidecars are needed for interrupted transfers, not successful ones.
            File(destination.absolutePath + ".aria2").delete()
            val elapsed = (System.nanoTime() - started) / 1.0e9
            Log.i(TAG, "Finished ${destination.name}: ${destination.length()} bytes in $elapsed s")
            onProgress(95f, "", "下载完成，校验媒体...", TaskStatus.PROCESSING)
            Result.success(destination)
        } catch (e: Exception) {
            if (cancelled.contains(taskId)) Result.failure(IOException("任务已取消", e))
            else {
                Log.w(TAG, "aria2 task failed: ${e.message}")
                Result.failure(e)
            }
        } finally {
            if (process != null) processes.remove(taskId, process)
            if (process?.isAlive == true) process.destroyForcibly()
        }
    }
}
