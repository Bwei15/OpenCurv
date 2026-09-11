package com.motoroute.data.traffic

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps [TrafficRepository] fresh from [AutobahnTrafficSource] whenever the
 * device has validated internet - and does nothing at all otherwise, because
 * the whole point of the offline-first cache is that a rider without signal
 * still gets the last known closures rather than an error.
 *
 * Triggers: app start ([start]), every network change (Wi-Fi <-> mobile <->
 * none, via `registerDefaultNetworkCallback`), and a 30-minute timer while
 * the app is in the foreground. Each trigger is a no-op if the last
 * successful fetch is younger than [minIntervalMillis], so switching
 * networks twice in a minute does not double-fetch, and a refresh only ever
 * replaces [TrafficRepository]'s incident list - it never recalculates an
 * active route, so a rider mid-navigation is not silently rerouted.
 *
 * This is the only Android-specific file in `data/traffic`
 * (`ConnectivityManager`, `SharedPreferences`), which is why it is excluded
 * from `tools/verifier` - see that build file's comment.
 */
class TrafficUpdater(
    context: Context,
    private val repository: TrafficRepository,
    private val scope: CoroutineScope,
    private val source: TrafficSource = AutobahnTrafficSource(),
    private val minIntervalMillis: Long = REFRESH_INTERVAL_MS,
) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var periodicJob: Job? = null

    /** Call once, from [com.motoroute.OpenCurvApp.onCreate]. Non-blocking. */
    fun start() {
        refreshIfDue(reason = "start")
        registerNetworkCallback()
        startPeriodicTimer()
    }

    /** Unregisters the network callback and stops the timer. Not currently called (app-scoped), kept for symmetry/tests. */
    fun stop() {
        periodicJob?.cancel()
        periodicJob = null
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // Already unregistered or connectivityManager gone - harmless.
            }
        }
        networkCallback = null
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshIfDue(reason = "network-available")
            }
        }
        networkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "could not register network callback", e)
        }
    }

    private fun startPeriodicTimer() {
        periodicJob = scope.launch {
            while (isActive) {
                delay(minIntervalMillis)
                refreshIfDue(reason = "periodic")
            }
        }
    }

    /** True only when the active network is both INTERNET-capable and platform-VALIDATED (actually reaches the internet, not just associated). */
    private fun hasValidatedInternet(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun refreshIfDue(reason: String) {
        if (repository.isRefreshing.value) return
        if (!hasValidatedInternet()) {
            Log.d(TAG, "skip refresh ($reason): no validated internet")
            return
        }
        val lastFetch = prefs.getLong(KEY_LAST_FETCH, 0L)
        val age = System.currentTimeMillis() - lastFetch
        if (lastFetch > 0L && age < minIntervalMillis) {
            Log.d(TAG, "skip refresh ($reason): cache is ${age / 1000}s old")
            return
        }
        scope.launch {
            repository.refreshFrom(source)
                .onSuccess { count ->
                    prefs.edit().putLong(KEY_LAST_FETCH, System.currentTimeMillis()).apply()
                    Log.i(TAG, "refreshed ($reason): $count incidents")
                }
                .onFailure { e ->
                    Log.w(TAG, "refresh failed ($reason): ${e.message}")
                }
        }
    }

    companion object {
        private const val TAG = "TrafficUpdater"
        private const val PREFS_NAME = "traffic_updater"
        private const val KEY_LAST_FETCH = "last_fetch_epoch_millis"
        const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L
    }
}
