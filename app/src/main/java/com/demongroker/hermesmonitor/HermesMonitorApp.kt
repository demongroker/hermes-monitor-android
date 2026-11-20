package com.demongroker.hermesmonitor

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class HermesMonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)

        // Polling-driven local alerts: 15 minutes is WorkManager's minimum period.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
    }

    companion object {
        const val UNIQUE_PERIODIC = "hermes-alert-poll"
        const val UNIQUE_IMMEDIATE = "hermes-alert-immediate"
    }
}
