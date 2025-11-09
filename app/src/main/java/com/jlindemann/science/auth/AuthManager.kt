package com.jlindemann.science.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

/**
 * Small helper to centralize Firebase<->Google sign-in logic.
 *
 * Usage:
 * - Build a GoogleSignInClient via buildGoogleSignInClient(context, webClientId)
 * - Launch its signInIntent from the Activity (ActivityResult)
 * - When you receive an idToken, call AuthManager.handleIdTokenForFirebase(idToken, onComplete)
 */
object AuthManager {
    private val firebaseAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    fun isSignedIn(): Boolean = firebaseAuth.currentUser != null

    fun getUid(): String? = firebaseAuth.currentUser?.uid

    fun getUserDisplayName(): String? = firebaseAuth.currentUser?.displayName
    fun getUserEmail(): String? = firebaseAuth.currentUser?.email

    fun signOut(googleClient: GoogleSignInClient? = null, onComplete: (() -> Unit)? = null) {
        try {
            firebaseAuth.signOut()
        } catch (_: Exception) { }
        try {
            googleClient?.signOut()?.addOnCompleteListener { onComplete?.invoke() } ?: onComplete?.invoke()
        } catch (_: Exception) {
            onComplete?.invoke()
        }
    }

    /**
     * Exchange Google idToken for a Firebase credential and sign in to Firebase.
     * onResult(true) when sign-in succeeded and Firebase user available.
     */
    fun handleIdTokenForFirebase(idToken: String, onResult: (Boolean, Exception?) -> Unit) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        firebaseAuth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    onResult(true, null)
                } else {
                    onResult(false, task.exception)
                }
            }
    }

    /**
     * Build a GoogleSignInClient configured to request an ID token for the provided webClientId.
     * Pass the web client id from your Firebase console (strings.xml).
     */
    fun buildGoogleSignInClient(activity: Activity, webClientId: String): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(activity, gso)
    }
}