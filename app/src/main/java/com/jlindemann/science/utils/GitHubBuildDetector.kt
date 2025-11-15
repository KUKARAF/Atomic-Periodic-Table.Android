package com.jlindemann.science.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

object GitHubBuildDetector {
    
    private const val GITHUB_BUILD_SIGNATURE = "github_release"
    
    fun isGitHubReleaseBuild(context: Context): Boolean {
        return try {
            // Check if installed from GitHub by looking at installer
            val packageManager = context.packageManager
            val packageInfo = packageManager.getPackageInfo(context.packageName, 0)
            
            // GitHub releases typically have no installer or are installed via package installer
            val installer = packageManager.getInstallerPackageName(packageInfo.packageName)
            
            // Check if it's a debug build or installed from unknown source (likely GitHub)
            val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            
            // For GitHub releases, we'll consider it a GitHub build if:
            // 1. It's debuggable (our CI builds are) OR
            // 2. No installer package (sideloaded) OR
            // 3. Installer is package installer (typical for APK installs)
            isDebuggable || installer == null || installer == "com.android.packageinstaller"
        } catch (e: Exception) {
            // If we can't determine, assume it's not a GitHub build
            false
        }
    }
    
    fun shouldUnlockForGitHub(context: Context): Boolean {
        return isGitHubReleaseBuild(context)
    }
}