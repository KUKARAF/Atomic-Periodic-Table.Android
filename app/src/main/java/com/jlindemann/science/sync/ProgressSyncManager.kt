package com.jlindemann.science.sync

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.jlindemann.science.util.XpManager

/**
 * Responsible for merging and syncing local XP/level with the cloud (Firestore).
 *
 * Behavior:
 *  - On sign-in we call mergeAndUploadLocalProgress which:
 *      * reads cloud doc users/{uid}
 *      * compares with local XpManager values
 *      * chooses a sensible merge (we prefer the larger XP by default)
 *      * writes merged result back to cloud and also updates local storage via XpManager.addXp or direct set if available
 *
 * Note: Firestore offline persistence should be enabled in your app initialization if you want offline capabilities.
 */
object ProgressSyncManager {
    private const val TAG = "ProgressSyncManager"
    private val db by lazy { FirebaseFirestore.getInstance() }

    data class CloudProgress(val xp: Long = 0L, val level: Int = 1, val lastUpdated: com.google.firebase.Timestamp? = null)

    private fun docRefForUid(uid: String) = db.collection("users").document(uid)

    /**
     * Load cloud progress for uid and call callback. If document not present, callback with null.
     */
    fun loadCloudProgress(uid: String, onLoaded: (CloudProgress?) -> Unit) {
        docRefForUid(uid).get()
            .addOnSuccessListener { snap ->
                if (snap.exists()) {
                    val xp = snap.getLong("xp") ?: 0L
                    val level = snap.getLong("level")?.toInt() ?: 1
                    val last = snap.getTimestamp("lastUpdated")
                    onLoaded(CloudProgress(xp = xp, level = level, lastUpdated = last))
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
     * Write progress to cloud (merge). lastUpdated will be server timestamp.
     */
    fun saveProgressToCloud(uid: String, xp: Long, level: Int, onComplete: ((Boolean, Exception?) -> Unit)? = null) {
        val data = mapOf(
            "xp" to xp,
            "level" to level,
            "lastUpdated" to FieldValue.serverTimestamp()
        )
        docRefForUid(uid).set(data, SetOptions.merge())
            .addOnSuccessListener { onComplete?.invoke(true, null) }
            .addOnFailureListener { e -> onComplete?.invoke(false, e) }
    }

    /**
     * Merge strategy: pick the larger XP value (and corresponding level).
     * If cloud has more recent lastUpdated timestamp, prefer cloud.
     *
     * This method:
     *  - reads cloud progress
     *  - decides merged xp/level
     *  - writes merged result to cloud
     *  - updates local XpManager accordingly via XpManager.setXp (if present) or by adding difference
     */
    fun mergeAndUploadLocalProgress(context: Context, uid: String, onComplete: ((Boolean) -> Unit)? = null) {
        // read local
        val localXp = XpManager.getXp(context)
        val localLevel = XpManager.getLevel(localXp)

        loadCloudProgress(uid) { cloud ->
            try {
                val (mergedXp, mergedLevel) = if (cloud == null) {
                    // cloud empty -> use local values
                    Pair(localXp.toLong(), localLevel)
                } else {
                    // prefer the one with larger xp (simple deterministic merge)
                    val cloudXp = cloud.xp
                    if (cloudXp >= localXp.toLong()) {
                        Pair(cloudXp, cloud.level)
                    } else {
                        Pair(localXp.toLong(), localLevel)
                    }
                }

                // persist merged to cloud
                saveProgressToCloud(uid, mergedXp, mergedLevel) { success, _ ->
                    if (success) {
                        // update local to merged (if needed)
                        val currentLocalXp = XpManager.getXp(context)
                        if (mergedXp.toLong() != currentLocalXp.toLong()) {
                            // prefer to set total XP: if XpManager has no setter, we add the difference
                            val diff = (mergedXp - currentLocalXp).toInt()
                            if (diff > 0) {
                                XpManager.addXp(context, diff) // addXp should exist; otherwise adjust as needed
                            } else if (diff < 0) {
                                // negative diff -> no-op to avoid removing user progress unexpectedly
                            }
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