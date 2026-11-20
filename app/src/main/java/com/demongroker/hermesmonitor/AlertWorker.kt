package com.demongroker.hermesmonitor

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray

/**
 * Polls GET /api/state with the stored Bearer token and posts a local
 * notification for every 'critical' alert that has not been notified yet.
 * Dedupe: alert ids already notified are kept in SharedPreferences.
 * This is polling-driven local notification, NOT server push.
 */
class AlertWorker(appContext: Context, params: WorkerParameters) :
    Worker(appContext, params) {

    override fun doWork(): Result {
        val store = SecureStore(applicationContext)
        if (!store.isConfigured) return Result.success()

        return try {
            val state = Api.fetchState(store.baseUrl, store.token)
            val alerts = state.optJSONArray("alerts") ?: JSONArray()

            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val notified = HashSet(prefs.getStringSet(KEY_NOTIFIED, emptySet()) ?: emptySet())
            var changed = false

            for (i in 0 until alerts.length()) {
                val alert = alerts.optJSONObject(i) ?: continue
                if (alert.optString("severity") != "critical") continue
                val id = alert.optString("id")
                if (id.isBlank() || notified.contains(id)) continue
                Notifications.postCritical(
                    applicationContext,
                    id,
                    alert.optString("message", "Critical alert")
                )
                notified.add(id)
                changed = true
            }

            if (changed) {
                // Bound the dedupe set so it cannot grow forever.
                val trimmed = if (notified.size > MAX_IDS) {
                    notified.toList().takeLast(MAX_IDS).toHashSet()
                } else {
                    notified
                }
                prefs.edit().putStringSet(KEY_NOTIFIED, trimmed).apply()
            }
            Result.success()
        } catch (e: Exception) {
            // Network/server hiccup: retry a few times with WorkManager backoff.
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val PREFS = "hm_alert_state"
        private const val KEY_NOTIFIED = "notified_ids"
        private const val MAX_IDS = 500
    }
}
