package dev.nasexplorer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keeps the process alive and shows a notification while transfers are running. */
class TransferService : Service() {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(1, build("Starting…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val tm = (application as NasApp).transfers
        job?.cancel()
        job = scope.launch {
            tm.tasks.collect { list ->
                val active = list.filter { it.active }
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val running = active.firstOrNull { it.state == TState.Running } ?: active.first()
                    val text = "${running.kind}: ${running.title}" +
                        if (active.size > 1) "  (+${active.size - 1} queued)" else ""
                    nm.notify(1, build(text, running.done, running.total))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, done: Long, total: Long): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("NAS Explorer")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(if (total > 0) 1000 else 0, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0)
            .build()

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "transfers"

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java))
            } catch (_: Exception) {
                // Transfers still run while the app is open even if the service can't start.
            }
        }
    }
}
