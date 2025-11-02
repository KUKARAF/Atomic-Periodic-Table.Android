package com.jlindemann.science.util

import android.content.Context
import android.content.SharedPreferences
import com.jlindemann.science.preferences.ProVersion
import com.jlindemann.science.preferences.ProPlusVersion

object LivesManager {
    private const val PREFS_NAME = "lives_prefs"
    private const val LIVES_KEY = "lives_count"
    private const val LAST_REFILL_KEY = "last_refill_time"
    private const val DEFAULT_MAX_LIVES = 30
    private const val PRO_MAX_LIVES = 60
    private const val REFILL_AMOUNT = 5
    private const val REFILL_INTERVAL_MS = 10 * 60 * 1000L // 10 min

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Determines max lives based on user tier (Default, Pro, ProPlus).
     */
    fun getMaxLives(context: Context): Int {
        val proPlus = ProPlusVersion(context)
        val pro = ProVersion(context)
        val proPlusValue = proPlus.getValue()
        val proValue = pro.getValue()
        return when {
            proPlusValue == 100 -> Int.MAX_VALUE // Unlimited lives
            proValue == 100 -> PRO_MAX_LIVES
            else -> DEFAULT_MAX_LIVES
        }
    }

    fun getLives(context: Context): Int {
        val maxLives = getMaxLives(context)
        val lives = getPrefs(context).getInt(LIVES_KEY, maxLives)
        return if (maxLives == Int.MAX_VALUE) Int.MAX_VALUE else lives.coerceAtMost(maxLives)
    }

    fun setLives(context: Context, lives: Int) {
        val maxLives = getMaxLives(context)
        val value = if (maxLives == Int.MAX_VALUE) Int.MAX_VALUE else lives.coerceIn(0, maxLives)
        getPrefs(context).edit().putInt(LIVES_KEY, value).apply()
        // If we've filled to max, reset last refill time so timer doesn't keep running
        if (value >= maxLives && maxLives != Int.MAX_VALUE) {
            getPrefs(context).edit().putLong(LAST_REFILL_KEY, System.currentTimeMillis()).apply()
        }
    }

    fun loseLife(context: Context): Boolean {
        val prefs = getPrefs(context)
        val maxLives = getMaxLives(context)
        val lives = getLives(context)
        if (maxLives == Int.MAX_VALUE) return true // Unlimited lives, never lose
        return if (lives > 0) {
            val newLives = lives - 1
            prefs.edit().putInt(LIVES_KEY, newLives).apply()
            // Ensure a refill baseline is set when lives drop below max for the first time
            if (prefs.getLong(LAST_REFILL_KEY, 0L) == 0L) {
                prefs.edit().putLong(LAST_REFILL_KEY, System.currentTimeMillis()).apply()
            }
            true
        } else {
            false
        }
    }

    fun loseLives(context: Context, count: Int): Boolean {
        val prefs = getPrefs(context)
        val maxLives = getMaxLives(context)
        val lives = getLives(context)
        if (maxLives == Int.MAX_VALUE) return true // Unlimited
        if (lives <= 0) return false
        val newLives = (lives - count).coerceAtLeast(0)
        prefs.edit().putInt(LIVES_KEY, newLives).apply()
        // Ensure a refill baseline is set when lives drop below max for the first time
        if (prefs.getLong(LAST_REFILL_KEY, 0L) == 0L) {
            prefs.edit().putLong(LAST_REFILL_KEY, System.currentTimeMillis()).apply()
        }
        return true
    }

    /**
     * Attempts to refill lives based on time passed since last refill baseline.
     * Returns true if lives were increased.
     *
     * NOTE: if LAST_REFILL_KEY is missing (0) we try to recover reasonably:
     * - If lives == 0 we assume at least one interval has passed (so user gets immediate refill).
     * - Otherwise we initialize baseline to now and do not refill immediately.
     */
    fun refillLivesIfNeeded(context: Context): Boolean {
        val prefs = getPrefs(context)
        val maxLives = getMaxLives(context)
        if (maxLives == Int.MAX_VALUE) return false // Unlimited, nothing to refill

        var lives = getLives(context)
        var lastRefill = prefs.getLong(LAST_REFILL_KEY, 0L)
        val now = System.currentTimeMillis()

        // Recover missing baseline:
        if (lastRefill == 0L && lives < maxLives) {
            if (lives == 0) {
                // If the user has 0 lives and we have no baseline, assume at least one interval has passed
                // so they should receive the first refill immediately.
                lastRefill = now - REFILL_INTERVAL_MS
                prefs.edit().putLong(LAST_REFILL_KEY, lastRefill).apply()
            } else {
                // For non-zero lives we cannot reliably infer when countdown started; initialize to now.
                lastRefill = now
                prefs.edit().putLong(LAST_REFILL_KEY, lastRefill).apply()
                // No refill on this immediate call because baseline is just set now.
                return false
            }
        }

        // If lives already at or above max, reset baseline to now and return
        if (lives >= maxLives) {
            prefs.edit().putLong(LAST_REFILL_KEY, now).apply()
            return false
        }

        val intervalsPassed = ((now - lastRefill) / REFILL_INTERVAL_MS).toInt()
        if (intervalsPassed > 0) {
            val refillLives = intervalsPassed * REFILL_AMOUNT
            val newLives = (lives + refillLives).coerceAtMost(maxLives)
            // Advance lastRefill by the number of full intervals consumed so that partial progress is preserved.
            val advancedRefillTime = lastRefill + intervalsPassed * REFILL_INTERVAL_MS
            prefs.edit()
                .putInt(LIVES_KEY, newLives)
                .putLong(LAST_REFILL_KEY, advancedRefillTime)
                .apply()
            return newLives > lives
        }
        return false
    }

    fun getMillisToRefill(context: Context): Long {
        val prefs = getPrefs(context)
        val maxLives = getMaxLives(context)
        val lives = getLives(context)
        if (maxLives == Int.MAX_VALUE || lives >= maxLives) return 0
        var lastRefill = prefs.getLong(LAST_REFILL_KEY, 0L)
        val now = System.currentTimeMillis()
        // If baseline is not set:
        if (lastRefill == 0L) {
            // Previously we returned 0 when lives == 0 which causes confusing UI ("0 seconds").
            // Return a full interval here to indicate the countdown until the first refill,
            // which matches how refillLivesIfNeeded recovers missing baseline (it treats missing baseline for 0 lives
            // as if one interval had already elapsed).
            return REFILL_INTERVAL_MS
        }
        val timeSinceLast = now - lastRefill
        val timeLeft = REFILL_INTERVAL_MS - (timeSinceLast % REFILL_INTERVAL_MS)
        return if (timeLeft > 0) timeLeft else 0
    }

    fun getRefillAmount(context: Context): Int {
        return REFILL_AMOUNT
    }

    fun getRefillIntervalMs(context: Context): Long {
        return REFILL_INTERVAL_MS
    }
}