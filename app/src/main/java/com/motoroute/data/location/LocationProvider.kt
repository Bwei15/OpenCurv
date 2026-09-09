package com.motoroute.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * GPS positions, smoothed.
 *
 * Deliberately built on the platform [LocationManager] rather than Google Play
 * Services: OpenCurv has to work on a de-Googled phone and has to be
 * publishable on F-Droid, and the fused provider buys nothing for a device that
 * is outdoors with a clear sky view anyway.
 *
 * Every fix is run through [KalmanFilter] before it leaves this class, so
 * consumers see a smooth position, speed and heading instead of the jitter a
 * handlebar mount produces.
 */
class LocationProvider(
    private val context: Context,
    private val filter: KalmanFilter = KalmanFilter(),
) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isGpsEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    }

    /** Last known position, unfiltered - used only to centre the map at startup. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        return runCatching {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
    }

    /**
     * A stream of filtered fixes at roughly 1 Hz.
     *
     * 1 Hz is what consumer GNSS chips actually produce; asking for more only
     * burns battery. Navigation interpolates between fixes for the map.
     */
    @SuppressLint("MissingPermission")
    fun fixes(minIntervalMillis: Long = 1000L): Flow<FilteredFix> = callbackFlow {
        if (!hasPermission()) {
            close(SecurityException("location permission not granted"))
            return@callbackFlow
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            close(IllegalStateException("no LocationManager"))
            return@callbackFlow
        }

        filter.reset()

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val fix = filter.update(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = if (location.hasAccuracy()) location.accuracy else 15f,
                    timestampMillis = location.time,
                    speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                    bearingDegrees = if (location.hasBearing()) location.bearing.toDouble() else null,
                )
                trySend(fix)
            }

            @Deprecated("Required by the pre-API-29 LocationListener contract")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        val providers = buildList {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            // Only fall back to the network provider when GPS is off; it is
            // useless for navigation but keeps the map roughly centred.
            if (isEmpty() && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }
        if (providers.isEmpty()) {
            close(IllegalStateException("no location provider enabled"))
            return@callbackFlow
        }

        providers.forEach { provider ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.requestLocationUpdates(
                    provider,
                    android.location.LocationRequest.Builder(minIntervalMillis)
                        .setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
                        .setMinUpdateDistanceMeters(0f)
                        .build(),
                    context.mainExecutor,
                    listener,
                )
            } else {
                @Suppress("DEPRECATION")
                manager.requestLocationUpdates(
                    provider,
                    minIntervalMillis,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
            }
        }

        awaitClose { manager.removeUpdates(listener) }
    }
}
