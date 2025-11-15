package com.jlindemann.science.preferences

import android.content.Context
import com.jlindemann.science.utils.GitHubBuildDetector

class ProVersion(context : Context) {

    val PREFERENCE_NAME = "Pro_Preference"
    val PREFERENCE_VALUE = "Pro_Value"

    val preference = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)

    // DO NOT CHANGE VALUE FROM 1 BEFORE PUSHING RELEASE
    fun getValue() : Int{
        // Auto-unlock for GitHub builds
        if (GitHubBuildDetector.shouldUnlockForGitHub(context)) {
            return 100 // PRO USER
        }
        return preference.getInt (PREFERENCE_VALUE, 1) //1 == NO PRO USER, 100 == PRO USER
    }

    fun setValue(count:Int) {
        val editor = preference.edit()
        editor.putInt(PREFERENCE_VALUE,count)
        editor.apply()
    }
}