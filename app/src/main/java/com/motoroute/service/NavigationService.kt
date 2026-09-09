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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleService
import com.motoroute.MainActivity
import com.motoroute.OpenCurvApp
import com.motoroute.R
import com.motoroute.domain.NavigationState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps guidance alive while the app is not in the foreground.
 *
 * The service holds no navigation logic - that lives in NavigationController,
 * which is process-scoped. What the service provides is the thing only a
 * foreground service can: permission to keep receiving GPS with the screen
 * off, and a notification the rider can use to stop the ride with gloves on.
 */
class NavigationService : LifecycleService() {

    private val container by lazy { (application as OpenCurvApp).container }

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification(null)

        lifecycleScope.launch {
            container.navigation.state.collectLatest { state ->
                if (!state.isNavigating) {
                    stopSelf()
                    return@collectLatest
                }
                notify(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            container.navigation.stopNavigation()
            stopSelf()
            return START_NOT_STICKY
        }
        container.navigation.startLocationUpdates()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startForegroundWithNotification(state: NavigationState?) {
        // A location foreground service needs the location permission, and the
        // platform throws rather than degrading if it is missing. Guidance
        // itself does not depend on this service - it keeps the GPS coming with
        // the screen off - so a refused permission should leave the rider with
        // a working app and no notification, not a crash.
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(state),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                },
            )
        }.onFailure { stopSelf() }
    }

    private fun notify(state: NavigationState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: NavigationState?): Notification {
        val content = state?.let {
            val km = it.remainingDistanceMeters / 1000.0
            val minutes = (it.remainingSeconds / 60.0).roundToInt()
            String.format("%.1f km  -  %d min", km, minutes)
        } ?: getString(R.string.calculating)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, OpenCurvApp.NAV_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_maneuver_straight)
            .setContentTitle(getString(R.string.nav_notification_title))
            .setContentText(content)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.nav_notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.motoroute.STOP_NAVIGATION"

        fun start(context: Context) {
            val intent = Intent(context, NavigationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NavigationService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
