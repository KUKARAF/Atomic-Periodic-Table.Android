package com.jlindemann.science.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.AlarmManagerCompat
import com.jlindemann.science.activities.tools.StreakReminderReceiver
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Lightweight streak tracker compatible with API 24+.
 *
 * Notes:
 * - Uses java.util.Calendar + SimpleDateFormat (no java.time) for broad API compatibility.
 * - Schedules a single exact alarm ~24h ahead when streak >= 3. The receiver reschedules itself daily
 *   while the streak requirement is still met.
 * - Uses defensive error handling; callers should still validate where appropriate.
 */
object StreakManager {
    private const val PREFS = "streak_prefs"
    private const val KEY_LAST_PLAY = "last_play_date" // ISO yyyy-MM-dd
    private const val KEY_STREAK = "current_streak"
    private const val KEY_BEST = "best_streak"
    private const val KEY_REMINDER_SCHEDULED = "reminder_scheduled"
    private const val REMINDER_ACTION = "com.jlindemann.science.STREAK_REMINDER"

    // Use a stable pattern and Locale.US to avoid locale-dependent formats
    private val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Call when the user has completed at least one play today.
     * Returns the new streak length.
     */
    fun recordPlay(ctx: Context): Int {
        val p = prefs(ctx)
        val todayS = todayString()

        val lastS = p.getString(KEY_LAST_PLAY, null)
        var streak = p.getInt(KEY_STREAK, 0)
        var best = p.getInt(KEY_BEST, 0)

        if (lastS == todayS) {
            // already recorded today
            return streak
        }

        if (lastS != null) {
            try {
                val lastDate = formatter.parse(lastS)
                val lastCal = Calendar.getInstance().apply { time = lastDate!! }
                val todayCal = Calendar.getInstance()
                // advance last by 1 day and compare year/day-of-year
                lastCal.add(Calendar.DAY_OF_YEAR, 1)
                val consecutive = lastCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
                        lastCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR)

                if (consecutive) {
                    // consecutive day
                    streak = streak + 1
                } else {
                    // not consecutive -> reset
                    streak = 1
                }
            } catch (e: Exception) {
                // parse error -> reset
                streak = 1
            }
        } else {
            // first recorded day
            streak = 1
        }

        if (streak > best) best = streak

        p.edit()
            .putString(KEY_LAST_PLAY, todayS)
            .putInt(KEY_STREAK, streak)
            .putInt(KEY_BEST, best)
            .apply()

        // If we reach or exceed 3 days, schedule the reminder; otherwise cancel
        if (streak >= 3) {
            scheduleReminder(ctx)
        } else {
            cancelReminder(ctx)
        }

        return streak
    }

    fun getCurrentStreak(ctx: Context): Int {
        return prefs(ctx).getInt(KEY_STREAK, 0)
    }

    fun getBestStreak(ctx: Context): Int {
        return prefs(ctx).getInt(KEY_BEST, 0)
    }

    fun resetStreak(ctx: Context) {
        val p = prefs(ctx)
        p.edit().remove(KEY_STREAK).remove(KEY_LAST_PLAY).apply()
        cancelReminder(ctx)
    }

    private fun todayString(): String {
        val cal = Calendar.getInstance()
        return formatter.format(cal.time)
    }

    private fun reminderPendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx.applicationContext, StreakReminderReceiver::class.java).apply {
            action = REMINDER_ACTION
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(
            ctx.applicationContext,
            0,
            intent,
            flags
        )
    }

    /**
     * Schedule a reminder ~24h from now. If a reminder already scheduled it will be replaced.
     * Uses AlarmManagerCompat.setExactAndAllowWhileIdle where available.
     */
    fun scheduleReminder(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)
            val pi = reminderPendingIntent(ctx)
            // Use compat helper for best behavior across API levels
            AlarmManagerCompat.setExactAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, triggerAt, pi)
            prefs(ctx).edit().putBoolean(KEY_REMINDER_SCHEDULED, true).apply()
        } catch (t: Throwable) {
            // don't crash the app for scheduling failures
            t.printStackTrace()
        }
    }

    /**
     * Cancel any scheduled reminder.
     */
    fun cancelReminder(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = reminderPendingIntent(ctx)
            am.cancel(pi)
            prefs(ctx).edit().putBoolean(KEY_REMINDER_SCHEDULED, false).apply()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    fun isReminderScheduled(ctx: Context): Boolean {
        return prefs(ctx).getBoolean(KEY_REMINDER_SCHEDULED, false)
    }
}