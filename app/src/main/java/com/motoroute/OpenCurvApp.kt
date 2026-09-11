package com.motoroute

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.motoroute.di.AppContainer
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

class OpenCurvApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // MapLibre needs its singleton set up before any MapView exists. No
        // API key/account: unlike Mapbox, MapLibre is not gated behind one.
        MapLibre.getInstance(this)

        container = AppContainer(this)
        createNotificationChannel()

        // Non-blocking: registers the network callback and does an initial
        // cache-age check, the actual HTTP fetch (if any) runs in its own launch.
        container.trafficUpdater.start()

        container.scope.launch {
            val version = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "dev"
            container.profileManager.ensureInstalled(version)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                NAV_CHANNEL_ID,
                getString(R.string.nav_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.nav_channel_desc)
                setShowBadge(false)
                enableVibration(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.download_channel_desc)
                setShowBadge(false)
                enableVibration(false)
            },
        )
    }

    companion object {
        const val NAV_CHANNEL_ID = "navigation"
        const val DOWNLOAD_CHANNEL_ID = "downloads"
    }
}
