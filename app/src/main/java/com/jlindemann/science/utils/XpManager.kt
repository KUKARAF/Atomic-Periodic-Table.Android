package com.jlindemann.science.util

import android.content.Context
import kotlin.math.roundToInt
import kotlin.math.pow

object XpManager {
    private const val XP_KEY = "user_xp"
    private const val PREFS = "xp_prefs"
    private const val LEVELS = 100

    // Reward prefs (stores the persistent XP bonus multiplier)
    private const val REWARDS_PREFS = "rewards_prefs"
    private const val XP_BONUS_KEY = "xp_bonus_multiplier"
    private const val DEFAULT_XP_MULTIPLIER = 1.0f

    // Custom XP table for first 21 levels for fine control, then scale up exponentially
    private val xpTable = intArrayOf(
        0,       // Level 1
        90,      // Level 2
        180,     // Level 3
        270,     // Level 4
        360,     // Level 5
        495,     // Level 6
        630,     // Level 7
        765,     // Level 8
        900,     // Level 9
        1050,    // Level 10
        1275,    // Level 11
        1500,    // Level 12
        1725,    // Level 13
        1950,    // Level 14
        2175,    // Level 15
        2550,    // Level 16
        2925,    // Level 17
        3300,    // Level 18
        3675,    // Level 19
        4050,    // Level 20
        4575     // Level 21
    )

    // After level 21, scale exponentially and then linearly for very high levels
    private fun xpToReachLevel(n: Int): Int {
        if (n <= 1) return 0
        if (n - 1 < xpTable.size) return xpTable[n - 1]
        // Exponential scaling after level 21, then slow to linear at high levels
        // This formula can be adjusted for balance
        val base = xpTable.last()
        val extraLevels = n - xpTable.size
        return (base + (600 * (1.02.pow(extraLevels) - 1) / 0.02)).roundToInt()
    }

    fun getLevel(xp: Int): Int {
        var level = 1
        while (level < LEVELS && xpToReachLevel(level + 1) <= xp) {
            level++
        }
        return level
    }

    fun getXpForLevel(level: Int): Int {
        return xpToReachLevel(level)
    }

    fun getXp(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(XP_KEY, 0)
    }

    /**
     * Add XP to the persistent store WITHOUT applying any reward multiplier.
     * Use this for administrative/manual XP adjustments or when you don't want
     * the game-completion multiplier applied.
     *
     * amount: actual XP to add (unmodified).
     */
    fun addXp(context: Context, amount: Int) {
        if (amount <= 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldXp = prefs.getInt(XP_KEY, 0)
        val newXp = oldXp + amount
        prefs.edit().putInt(XP_KEY, newXp).apply()
    }

    /**
     * Add XP that originates from completing a game/quiz.
     * This method applies the persistent XP bonus multiplier (e.g. claimed +5% rewards).
     *
     * amount: base XP awarded for the game (before multiplier).
     */
    fun addGameXp(context: Context, amount: Int) {
        if (amount <= 0) return
        val multiplier = getXpBonusMultiplier(context).coerceAtLeast(DEFAULT_XP_MULTIPLIER)
        val finalAmount = (amount * multiplier).roundToInt()

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldXp = prefs.getInt(XP_KEY, 0)
        val newXp = oldXp + finalAmount
        prefs.edit().putInt(XP_KEY, newXp).apply()
    }

    fun getCurrentLevel(context: Context): Int {
        return getLevel(getXp(context))
    }

    fun getXpProgressInLevel(context: Context): Pair<Int, Int> {
        val xp = getXp(context)
        val level = getLevel(xp)
        val minXp = getXpForLevel(level)
        val maxXp = getXpForLevel(level + 1)
        return Pair(xp - minXp, maxXp - minXp)
    }

    // ---- XP bonus multiplier helpers (stored in separate prefs file) ----

    /**
     * Returns the current persistent XP multiplier (default 1.0f).
     * Example: 1.05f means +5% to awarded game XP.
     */
    fun getXpBonusMultiplier(context: Context): Float {
        return context.getSharedPreferences(REWARDS_PREFS, Context.MODE_PRIVATE)
            .getFloat(XP_BONUS_KEY, DEFAULT_XP_MULTIPLIER)
    }

    /**
     * Adds delta to the stored XP multiplier and persists it.
     * Use a small positive delta (e.g. 0.05f for +5%).
     */
    fun addXpBonusMultiplier(context: Context, delta: Float) {
        if (delta == 0f) return
        val prefs = context.getSharedPreferences(REWARDS_PREFS, Context.MODE_PRIVATE)
        val curr = prefs.getFloat(XP_BONUS_KEY, DEFAULT_XP_MULTIPLIER)
        prefs.edit().putFloat(XP_BONUS_KEY, curr + delta).apply()
    }

    /**
     * Overwrite the stored multiplier with an explicit value (e.g. for tests/admin).
     */
    fun setXpBonusMultiplier(context: Context, multiplier: Float) {
        val prefs = context.getSharedPreferences(REWARDS_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putFloat(XP_BONUS_KEY, multiplier.coerceAtLeast(DEFAULT_XP_MULTIPLIER)).apply()
    }
}