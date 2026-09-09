package com.motoroute.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.motoroute.MainActivity
import com.motoroute.OpenCurvApp
import com.motoroute.R
import com.motoroute.data.download.DownloadQueueState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps a map download running while the rider does something else.
 *
 * A region file is hundreds of megabytes; without a foreground service the
 * download dies the moment the screen locks, which on a phone means it
 * effectively never finishes. The service holds no logic - the queue lives in
 * DownloadRepository - it exists to keep the process alive and to show
 * progress the rider can cancel.
 */
class DownloadService : LifecycleService() {

    private val container by lazy { (application as OpenCurvApp).container }

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification(null)

        lifecycleScope.launch {
            container.downloads.state.collectLatest { state ->
                if (!state.isRunning) {
                    stopSelf()
                    return@collectLatest
                }
                notify(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_CANCEL) {
            container.downloads.cancelAll()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startForegroundWithNotification(state: DownloadQueueState?) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(state),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun notify(state: DownloadQueueState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: DownloadQueueState?): Notification {
        val active = state?.active
        val title = active?.target?.label ?: getString(R.string.download_preparing)
        val percent = active?.fraction?.let { (it * 100).roundToInt() }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this,
            2,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, OpenCurvApp.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_action_import)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(title)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.action_cancel), cancel)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, percent ?: 0, percent == null)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 43
        const val ACTION_CANCEL = "com.motoroute.CANCEL_DOWNLOADS"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
