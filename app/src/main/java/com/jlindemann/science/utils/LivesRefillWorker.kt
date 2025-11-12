package com.jlindemann.science.activities.tools

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jlindemann.science.util.LivesManager
import com.jlindemann.science.util.NotificationHelper

/**
 * Background worker that periodically runs (scheduled by LivesManager) and:
 *  - calls refillLivesIfNeeded(context) to update lives counters
 *  - if a refill happened and the user had previously dropped under threshold, and is now full,
 *    it triggers a notification (via NotificationHelper).
 *
 * This worker runs on WorkManager's background thread; keep work fast.
 */
class LivesRefillWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        try {
            // Capture pre-refill lives to update "was under threshold" state
            val pre = LivesManager.getLives(applicationContext)

            // Try to refill; this returns true if at least one life was added
            val refilled = LivesManager.refillLivesIfNeeded(applicationContext)

            val post = LivesManager.getLives(applicationContext)
            // If a refill happened and post >= maxLives and the user was previously under 10, notify
            val maxLives = LivesManager.getMaxLives(applicationContext)
            val prefs = applicationContext.getSharedPreferences("lives_notifications_prefs", Context.MODE_PRIVATE)
            val wasUnder10 = prefs.getBoolean("was_under_10", false)

            // update was_under_10 flag if we are currently under threshold
            if (pre < 10) {
                prefs.edit().putBoolean("was_under_10", true).apply()
            }

            if (refilled && wasUnder10 && post >= maxLives) {
                NotificationHelper.sendLivesRefilledNotification(applicationContext)
                prefs.edit().putBoolean("was_under_10", false).apply()
            }

            // Ensure the worker remains scheduled by LivesManager (idempotent)
            LivesManager.ensureRefillWorkerScheduled(applicationContext)

            return Result.success()
        } catch (t: Throwable) {
            t.printStackTrace()
            return Result.retry()
        }
    }
}