package com.omni.downloader.engine

import android.content.Context
import android.util.Log
import com.omni.downloader.data.model.TaskStatus
import java.io.File
import java.io.IOException

/**
 * aria2 is always first for new HTTP downloads, OkHttp is the recovery path.
 * Interrupted HTTP resumes stay in that engine until finished, so aria2 never
 * overwrites an authenticated contiguous HTTP partial file.
 */
internal object NativeTransfer {
    fun reset(taskId: String) {
        Aria2Downloader.resetCancellation(taskId)
        HttpFallbackDownloader.reset(taskId)
    }

    fun cancel(taskId: String) {
        Aria2Downloader.cancel(taskId)
        HttpFallbackDownloader.cancel(taskId)
    }

    fun download(
        context: Context,
        taskId: String,
        url: String,
        destination: File,
        headers: Map<String, String> = emptyMap(),
        proxyUrl: String = "",
        progress: (Float, String, String, TaskStatus) -> Unit
    ): Result<File> {
        if (Aria2Downloader.isCancelled(taskId)) {
            return Result.failure(IOException("下载已取消"))
        }

        // Use the same validated HTTP resume record across process restarts.
        if (File(destination.absolutePath + ".httpresume").exists()) {
            progress(1f, "", "恢复 HTTP 断点续传...", TaskStatus.DOWNLOADING)
            return HttpFallbackDownloader.download(
                context, taskId, url, destination, headers, proxyUrl, progress
            )
        }

        val native = Aria2Downloader.download(
            context, taskId, url, destination, headers, proxyUrl, progress
        )
        if (native.isSuccess) return native
        if (Aria2Downloader.isCancelled(taskId)) {
            return Result.failure(IOException("下载已取消", native.exceptionOrNull()))
        }

        // Aria2 may have left out-of-order parts. They cannot be treated as a
        // contiguous stream by OkHttp, even if their file length is nonzero.
        destination.delete()
        File(destination.absolutePath + ".aria2").delete()
        File(destination.absolutePath + ".aria2source").delete()

        progress(1f, "", "aria2 失败，切换 HTTP 兼容下载...", TaskStatus.DOWNLOADING)
        val fallback = HttpFallbackDownloader.download(
            context, taskId, url, destination, headers, proxyUrl, progress
        )
        if (fallback.isSuccess) return fallback
        if (Aria2Downloader.isCancelled(taskId)) {
            return Result.failure(IOException("下载已取消"))
        }

        val firstReason = native.exceptionOrNull()?.message.orEmpty().take(500)
        val secondReason = fallback.exceptionOrNull()?.message.orEmpty().take(400)
        Log.w("NativeTransfer", "Both native and HTTP transfers failed: $firstReason ; $secondReason")
        return Result.failure(
            IOException("下载失败。aria2：$firstReason；HTTP 兼容通道：$secondReason",
                fallback.exceptionOrNull())
        )
    }
}
