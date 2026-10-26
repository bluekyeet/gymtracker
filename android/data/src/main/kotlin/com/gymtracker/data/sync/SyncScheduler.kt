package com.gymtracker.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules background sync. 15 minutes is WorkManager's minimum periodic interval; an immediate
 *  one-off run on app foreground, and on regaining internet, is what makes sync feel chat-app-fast
 *  in practice (push/pull are already delta-only, so firing them often is cheap). */
object SyncScheduler {
    private const val PERIODIC_NAME = "sync_periodic"
    private const val FOREGROUND_NAME = "sync_foreground"
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun schedulePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Fire-and-forget sync, used on app launch/foreground so changes show up without waiting 15 min. */
    fun syncNow(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            FOREGROUND_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** Phone only: fire a sync the instant the device regains internet, so changes made offline
     *  (or on the watch) show up within seconds of reconnecting instead of waiting for the next
     *  15-minute tick. Call once from Application.onCreate; safe to call again (replaces listener).
     *  Not used on the watch — battery there matters more than instant updates. */
    fun watchForConnectivity(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkCallback?.let { cm.unregisterNetworkCallback(it) }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = syncNow(context.applicationContext)
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
    }
}
