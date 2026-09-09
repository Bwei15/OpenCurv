package com.motoroute

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.motoroute.di.AppContainer
import kotlinx.coroutines.launch
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

class OpenCurvApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // Mapsforge needs its graphics factory before any MapView exists.
        AndroidGraphicFactory.createInstance(this)

        container = AppContainer(this)
        createNotificationChannel()

        container.scope.launch {
            val version = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "dev"
            container.profileManager.ensureInstalled(version)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NAV_CHANNEL_ID,
            getString(R.string.nav_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.nav_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val NAV_CHANNEL_ID = "navigation"
    }
}
