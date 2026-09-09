package com.motoroute

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.motoroute.di.AppContainer
import com.motoroute.diagnostics.CrashLog
import kotlinx.coroutines.launch
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

class OpenCurvApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // First thing in the process: a crash during any of the setup below is
        // exactly the kind that leaves a rider with a closed app and no idea
        // why.
        CrashLog.install(this)

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
