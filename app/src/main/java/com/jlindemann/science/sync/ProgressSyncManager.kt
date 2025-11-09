package com.jlindemann.science.sync

import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.jlindemann.science.model.Achievement
import com.jlindemann.science.model.AchievementModel
import com.jlindemann.science.model.Statistics
import com.jlindemann.science.model.StatisticsModel
import com.jlindemann.science.util.XpManager
import com.jlindemann.science.utils.StreakManager

object ProgressSyncManager {
    private const val TAG = "ProgressSyncManager"
    private val db by lazy { FirebaseFirestore.getInstance() }

    data class CloudAchievement(val id: Int = 0, val progress: Int = 0, val maxProgress: Int? = null)
    data class CloudProgress(
        val xp: Long = 0L,
        val level: Int = 1,
        val lastUpdated: Timestamp? = null,
        val achievements: List<CloudAchievement>? = null,
        val statistics: Map<Int, Long>? = null,
        val streak: Long? = null
    )

    private fun docRefForUid(uid: String) = db.collection("users").document(uid)

    /**
     * Load cloud progress for uid and call callback. If document not present, callback with null.
     * Also attempts to read achievements (list of maps), statistics (map of id->progress) and streak.
     */
    fun loadCloudProgress(uid: String, onLoaded: (CloudProgress?) -> Unit) {
        docRefForUid(uid).get()
            .addOnSuccessListener { snap ->
                if (snap.exists()) {
                    try {
                        val xp = snap.getLong("xp") ?: 0L
                        val level = snap.getLong("level")?.toInt() ?: 1
                        val last = snap.getTimestamp("lastUpdated")

                        val cloudAchievements: List<CloudAchievement>? = (snap.get("achievements") as? List<*>)?.mapNotNull { item ->
                            (item as? Map<*, *>)?.let { map ->
                                val idAny = map["id"]
                                val progAny = map["progress"]
                                val maxAny = map["maxProgress"]
                                val id = when (idAny) {
                                    is Number -> idAny.toInt()
                                    is String -> idAny.toIntOrNull()
                                    else -> null
                                }
                                val prog = when (progAny) {
                                    is Number -> progAny.toInt()
                                    is String -> progAny.toIntOrNull()
                                    else -> null
                                }
                                val maxP = when (maxAny) {
                                    is Number -> maxAny.toInt()
                                    is String -> maxAny.toIntOrNull()
                                    else -> null
                                }
                                if (id != null && prog != null) {
                                    CloudAchievement(id = id, progress = prog, maxProgress = maxP)
                                } else null
                            }
                        }

                        val cloudStats: Map<Int, Long>? = (snap.get("statistics") as? Map<*, *>)?.mapNotNull { entry ->
                            val key = entry.key
                            val value = entry.value
                            val id = when (key) {
                                is String -> key.toIntOrNull()
                                is Number -> key.toInt()
                                else -> null
                            }
                            val progLong = when (value) {
                                is Number -> value.toLong()
                                is String -> value.toLongOrNull()
                                else -> null
                            }
                            if (id != null && progLong != null) Pair(id, progLong) else null
                        }?.toMap()

                        val cloudStreak: Long? = when (val s = snap.get("streak")) {
                            is Number -> s.toLong()
                            is String -> s.toLongOrNull()
                            else -> null
                        }

                        onLoaded(CloudProgress(xp = xp, level = level, lastUpdated = last, achievements = cloudAchievements, statistics = cloudStats, streak = cloudStreak))
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to parse cloud progress", t)
                        onLoaded(null)
                    }
                } else {
                    onLoaded(null)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "loadCloudProgress failed", e)
                onLoaded(null)
            }
    }

    /**
     * Write progress and optionally achievements/statistics/streak to cloud (merge). lastUpdated will be server timestamp.
     */
    fun saveFullProgressToCloud(
        uid: String,
        xp: Long,
        level: Int,
        achievements: List<Achievement>? = null,
        statistics: List<Statistics>? = null,
        streak: Int? = null,
        onComplete: ((Boolean, Exception?) -> Unit)? = null
    ) {
        val data = mutableMapOf<String, Any>(
            "xp" to xp,
            "level" to level,
            "lastUpdated" to FieldValue.serverTimestamp()
        )

        achievements?.let { list ->
            val achMaps = list.map { ach ->
                mapOf(
                    "id" to ach.id,
                    "title" to ach.title,
                    "progress" to ach.progress,
                    "maxProgress" to ach.maxProgress
                )
            }
            data["achievements"] = achMaps
        }

        statistics?.let { stats ->
            val statMap = stats.associate { stat ->
                stat.id.toString() to stat.progress
            }
            data["statistics"] = statMap
        }

        streak?.let {
            data["streak"] = it
        }

        docRefForUid(uid).set(data, SetOptions.merge())
            .addOnSuccessListener { onComplete?.invoke(true, null) }
            .addOnFailureListener { e -> onComplete?.invoke(false, e) }
    }

    /**
     * Merge strategy:
     * - For xp: keep whichever xp is larger (existing behavior).
     * - For achievements/statistics/streak: if cloud.xp >= localXp, prefer cloud values; otherwise prefer local.
     *
     * When updating local storage, we only increase per-item progress (we never decrease local progress).
     */
    fun mergeAndUploadLocalProgress(context: Context, uid: String, onComplete: ((Boolean) -> Unit)? = null) {
        // read local xp/level
        val localXp = XpManager.getXp(context)
        val localLevel = XpManager.getLevel(localXp)

        // read local achievements & statistics using existing model getters
        val localAchievements = ArrayList<Achievement>()
        AchievementModel.getList(context, localAchievements)
        // Ensure each item's progress is loaded (in case getList doesn't)
        localAchievements.forEach { it.loadProgress(context) }

        val localStatistics = ArrayList<Statistics>()
        StatisticsModel.getList(context, localStatistics)
        localStatistics.forEach { it.loadProgress(context) }

        // read local streak
        val localStreak = try {
            StreakManager.getCurrentStreak(context)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read local streak", t)
            0
        }

        loadCloudProgress(uid) { cloud ->
            try {
                val (mergedXp, mergedLevel) = if (cloud == null) {
                    // cloud empty -> use local values
                    Pair(localXp.toLong(), localLevel)
                } else {
                    val cloudXp = cloud.xp
                    if (cloudXp >= localXp.toLong()) {
                        Pair(cloudXp, cloud.level)
                    } else {
                        Pair(localXp.toLong(), localLevel)
                    }
                }

                // Decide merged achievements & statistics & streak
                val mergedAchievements: List<Achievement> = if (cloud?.achievements != null && cloud.xp >= localXp.toLong()) {
                    // prefer cloud achievements
                    val localById = localAchievements.associateBy { it.id }
                    cloud.achievements.map { ca ->
                        val local = localById[ca.id]
                        if (local != null) {
                            Achievement(local.id, local.title, local.description, ca.progress, local.maxProgress)
                        } else {
                            Achievement(ca.id, "achievement_${ca.id}", "", ca.progress, ca.maxProgress ?: ca.progress)
                        }
                    }
                } else {
                    // prefer local achievements
                    localAchievements
                }

                val mergedStatistics: List<Statistics> = if (cloud?.statistics != null && cloud.xp >= localXp.toLong()) {
                    val localById = localStatistics.associateBy { it.id }
                    cloud.statistics.map { (id, progLong) ->
                        val local = localById[id]
                        if (local != null) {
                            Statistics(local.id, local.title, progLong.toInt())
                        } else {
                            Statistics(id, "stat_$id", progLong.toInt())
                        }
                    }
                } else {
                    localStatistics
                }

                val mergedStreak: Int = if (cloud?.streak != null && cloud.xp >= localXp.toLong()) {
                    cloud.streak.toInt()
                } else {
                    localStreak
                }

                // persist merged to cloud (write full state)
                saveFullProgressToCloud(uid, mergedXp, mergedLevel, mergedAchievements, mergedStatistics, mergedStreak) { success, _ ->
                    if (success) {
                        // update local to merged (only increase progress; do not reduce)
                        // Achievements: for each merged achievement, if merged.progress > current local progress -> increment by diff
                        val localByIdMutable = localAchievements.associateBy { it.id }.toMutableMap()
                        mergedAchievements.forEach { mergedAch ->
                            val local = localByIdMutable[mergedAch.id]
                            if (local != null) {
                                val current = local.progress
                                val target = mergedAch.progress
                                val diff = target - current
                                if (diff > 0) {
                                    // incrementProgress will save progress
                                    local.incrementProgress(context, diff)
                                }
                            } else {
                                val newAch = Achievement(mergedAch.id, mergedAch.title, mergedAch.description, 0, mergedAch.maxProgress)
                                val diff = mergedAch.progress - 0
                                if (diff > 0) newAch.incrementProgress(context, diff)
                            }
                        }

                        // Statistics: similar - only increase via incrementProgress
                        val localStatsById = localStatistics.associateBy { it.id }.toMutableMap()
                        mergedStatistics.forEach { mergedStat ->
                            val local = localStatsById[mergedStat.id]
                            if (local != null) {
                                val current = local.progress
                                val target = mergedStat.progress
                                val diff = target - current
                                if (diff > 0) {
                                    local.incrementProgress(context, diff)
                                }
                            } else {
                                val newStat = Statistics(mergedStat.id, mergedStat.title, 0)
                                val diff = mergedStat.progress - 0
                                if (diff > 0) newStat.incrementProgress(context, diff)
                            }
                        }

                        // Streak: increase local streak if merged is greater (do not reduce)
                        try {
                            val currentLocalStreak = StreakManager.getCurrentStreak(context)
                            if (mergedStreak > currentLocalStreak) {
                                StreakManager.setCurrentStreak(context, mergedStreak)
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to update local streak", t)
                        }

                        onComplete?.invoke(true)
                    } else {
                        onComplete?.invoke(false)
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                onComplete?.invoke(false)
            }
        }
    }
}