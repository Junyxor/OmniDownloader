package com.omni.downloader.engine

import android.content.Context
import com.omni.downloader.data.model.TaskStatus
import java.io.File
import java.io.IOException

/**
 * Single download engine for HTTP media transfers: aria2.
 *
 * Never destroy aria2 partial files on transient failures. Keeping .aria2
 * sidecars allows subsequent requests to continue at the last known point.
 * An authenticated, expired signed URL is handled by Aria2Downloader's
 * source fingerprint guard, which resets incompatible partial files.
 *
 * OkHttp remains available to parsers for fetching metadata, never as a
 * substitute media downloader.
 */
internal object NativeTransfer {
    fun reset(taskId: String) = Aria2Downloader.resetCancellation(taskId)

    fun cancel(taskId: String) = Aria2Downloader.cancel(taskId)

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

        // The old OkHttp .httpresume file must never be reused as an aria2
        // segment-control file. Discard it and any contiguous HTTP leftovers.
        val legacyResume = File(destination.absolutePath + ".httpresume")
        if (legacyResume.exists()) {
            legacyResume.delete()
            destination.delete()
            File(destination.absolutePath + ".aria2").delete()
            File(destination.absolutePath + ".aria2source").delete()
        }

        // Aria2Downloader validates the URL, headers and binary availability.
        // Do not delete its partial data after a network error.
        return Aria2Downloader.download(
            context, taskId, url, destination, headers, proxyUrl, progress
        )
    }
}
