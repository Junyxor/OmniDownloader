package com.omni.downloader.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.omni.downloader.R
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.data.repository.TaskRepository
import com.omni.downloader.engine.DownloadEngine
import com.omni.downloader.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager
    private lateinit var repository: TaskRepository

    private val taskQueue = ConcurrentLinkedQueue<DownloadTask>()
    // A pause retains media parts and metadata. Resume while shutdown is still in
    // progress is deferred until the old download worker has fully exited.
    private val pausedTaskIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val resumeAfterStop = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var isProcessing = false
    @Volatile
    private var currentRunningTaskId: String? = null

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "OmniDownloader:DownloadWakeLock")?.apply {
                setReferenceCounted(false)
            }
        }
        try {
            if (wakeLock?.isHeld == false) {
                // 最多持有 60 分钟防止异常泄漏，保障息屏不断流
                wakeLock?.acquire(60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        repository = TaskRepository.getInstance(applicationContext)
        createNotificationChannel()
    }

    @Synchronized
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_TASK_ID)
        when (intent?.action) {
            ACTION_START_TASK, ACTION_RESUME_TASK -> {
                if (id == null) return START_NOT_STICKY
                val task = repository.getTask(id) ?: return START_NOT_STICKY
                if (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.CANCELLED) {
                    return START_NOT_STICKY
                }
                if (id == currentRunningTaskId && id in pausedTaskIds) {
                    // Do not start a second writer while aria2 is shutting down.
                    resumeAfterStop.add(id)
                } else if (id != currentRunningTaskId && taskQueue.none { it.id == id }) {
                    pausedTaskIds.remove(id)
                    resumeAfterStop.remove(id)
                    repository.updateTaskProgress(id, task.progress, "", "", TaskStatus.PENDING)
                    taskQueue.offer(task)
                    processNextTask()
                }
            }
            ACTION_PAUSE_TASK -> {
                if (id == null) return START_NOT_STICKY
                val task = repository.getTask(id) ?: return START_NOT_STICKY
                if (task.status in setOf(TaskStatus.PENDING, TaskStatus.DOWNLOADING, TaskStatus.PROCESSING)) {
                    pausedTaskIds.add(id)
                    resumeAfterStop.remove(id)
                    taskQueue.removeIf { it.id == id }
                    repository.updateTaskProgress(id, task.progress, "", "", TaskStatus.PAUSED)
                    if (currentRunningTaskId == id) DownloadEngine.cancelTask(id)
                }
                if (!isProcessing) processNextTask()
            }
            ACTION_CANCEL_TASK -> {
                if (id == null) return START_NOT_STICKY
                pausedTaskIds.remove(id)
                resumeAfterStop.remove(id)
                taskQueue.removeIf { it.id == id }
                if (currentRunningTaskId == id) {
                    DownloadEngine.cancelTask(id)
                    currentRunningTaskId = null
                }
                repository.updateTaskProgress(id, 0f, "", "", TaskStatus.CANCELLED)
                if (!isProcessing) processNextTask()
            }
            ACTION_CANCEL_ALL -> {
                currentRunningTaskId?.let { DownloadEngine.cancelTask(it) }
                currentRunningTaskId = null
                taskQueue.clear()
                pausedTaskIds.clear()
                resumeAfterStop.clear()
                // The worker owns its lifecycle and will release its wake lock.
            }
        }
        return START_NOT_STICKY
    }

    @Synchronized
    private fun processNextTask() {
        if (isProcessing) return
        var next: DownloadTask? = null
        while (true) {
            val queued = taskQueue.poll() ?: break
            val live = repository.getTask(queued.id) ?: continue
            if (live.status == TaskStatus.PAUSED || live.status == TaskStatus.CANCELLED ||
                live.status == TaskStatus.COMPLETED) continue
            next = live
            break
        }
        if (next == null) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        val active = next
        repository.updateTaskProgress(active.id, active.progress, "", "", TaskStatus.DOWNLOADING)

        acquireWakeLock()
        isProcessing = true
        currentRunningTaskId = active.id
        startForeground(NOTIFICATION_ID, buildProgressNotification(active, 0f, "准备下载..."))

        serviceScope.launch {
            val proxy = com.omni.downloader.data.repository.SettingsRepository.getInstance(applicationContext).proxyUrl.value
            var lastNotificationTime = 0L
            var lastProgressUpdateTime = 0L
            var lastStatus: TaskStatus? = null
            var lastReportedProgress = -1f
            var lastReportedSpeed = ""

            val result = DownloadEngine.executeDownload(applicationContext, active, proxy) { progress, speed, eta, status ->
                synchronized(this@DownloadService) {
                    if (currentRunningTaskId != active.id || active.id in pausedTaskIds) return@executeDownload
                    val now = android.os.SystemClock.elapsedRealtime()
                    val isStatusChanged = status != lastStatus

                    // 1. UI 状态节流：状态发生转换时立即可靠送达；同状态内至少间隔 300ms 且有有效进度/速度变动才更新
                    val isProgressEffective = kotlin.math.abs(progress - lastReportedProgress) >= 0.5f ||
                            speed != lastReportedSpeed ||
                            progress >= 100f
                    if (isStatusChanged || (now - lastProgressUpdateTime >= 300 && isProgressEffective)) {
                        lastProgressUpdateTime = now
                        lastStatus = status
                        lastReportedProgress = progress
                        lastReportedSpeed = speed
                        repository.updateTaskProgress(active.id, progress, speed, eta, status)
                    }

                    // 2. 系统通知 Binder 节流：状态转换立即更新；同状态内至少间隔 800ms，彻底消除 IPC 造成的全局掉帧
                    if ((status == TaskStatus.DOWNLOADING || status == TaskStatus.PROCESSING) &&
                        (isStatusChanged || now - lastNotificationTime >= 800)) {
                        lastNotificationTime = now
                        val statusText = if (status == TaskStatus.PROCESSING) {
                            eta.ifBlank { "正在合并音视频..." }
                        } else {
                            "$speed ${if (eta.isNotEmpty()) "· 剩余 $eta" else ""}"
                        }
                        notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(active, progress, statusText, status))
                    }
                }
            }

            synchronized(this@DownloadService) {
                // Pause is not a failure: the native downloader has stopped and
                // its .aria2 / .httpresume file remains available for a future run.
                if (active.id in pausedTaskIds) {
                    if (resumeAfterStop.remove(active.id)) {
                        pausedTaskIds.remove(active.id)
                        repository.getTask(active.id)?.let { saved ->
                            repository.updateTaskProgress(saved.id, saved.progress, "", "", TaskStatus.PENDING)
                            if (taskQueue.none { it.id == saved.id }) taskQueue.offer(saved)
                        }
                    }
                } else if (currentRunningTaskId == active.id) {
                    result.onSuccess { finalPath ->
                        repository.markCompleted(active.id, finalPath)
                        MediaScannerConnection.scanFile(
                            applicationContext, arrayOf(finalPath), null
                        ) { _, _ -> }
                        showCompletedNotification(active)
                    }.onFailure { err ->
                        repository.markFailed(active.id, err.message ?: "下载过程中遇到错误")
                    }
                }
                isProcessing = false
                currentRunningTaskId = null
                processNextTask()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "下载任务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "展示当前视频/音频的实时下载与合并进度"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(
        task: DownloadTask,
        progress: Float,
        subText: String,
        status: TaskStatus = TaskStatus.DOWNLOADING
    ) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${if (status == TaskStatus.PROCESSING) "正在处理" else "正在下载"}: ${task.title}")
            .setContentText(subText)
            .setProgress(100, progress.toInt(), progress <= 0f)
            .setOngoing(true)
            .setContentIntent(getLaunchPendingIntent())
            .build()

    private fun showCompletedNotification(task: DownloadTask) {
        val completedNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("下载完成")
            .setContentText(task.title)
            .setAutoCancel(true)
            .setContentIntent(getLaunchPendingIntent())
            .build()

        notificationManager.notify(task.id.hashCode(), completedNotification)
    }

    private fun getLaunchPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "omni_download_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_TASK = "com.omni.downloader.START_TASK"
        const val ACTION_CANCEL_TASK = "com.omni.downloader.CANCEL_TASK"
        const val ACTION_PAUSE_TASK = "com.omni.downloader.PAUSE_TASK"
        const val ACTION_RESUME_TASK = "com.omni.downloader.RESUME_TASK"
        const val ACTION_CANCEL_ALL = "com.omni.downloader.CANCEL_ALL"
        const val EXTRA_TASK_ID = "extra_task_id"

        fun startDownload(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_TASK
                putExtra(EXTRA_TASK_ID, taskId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pauseDownload(context: Context, taskId: String) {
            context.startService(Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE_TASK
                putExtra(EXTRA_TASK_ID, taskId)
            })
        }

        fun resumeDownload(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME_TASK
                putExtra(EXTRA_TASK_ID, taskId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun cancelDownload(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_TASK
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }

        fun cancelAllDownloads(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_ALL
            }
            context.startService(intent)
        }
    }
}
