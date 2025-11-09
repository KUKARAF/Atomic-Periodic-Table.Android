package com.jlindemann.science.activities

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.jlindemann.science.R
import com.jlindemann.science.adapter.AchievementAdapter
import com.jlindemann.science.auth.AuthManager
import com.jlindemann.science.model.Achievement
import com.jlindemann.science.model.AchievementModel
import com.jlindemann.science.model.ConstantsModel
import com.jlindemann.science.model.Statistics
import com.jlindemann.science.model.StatisticsModel
import com.jlindemann.science.preferences.ProPlusVersion
import com.jlindemann.science.preferences.ProVersion
import com.jlindemann.science.preferences.ThemePreference
import com.jlindemann.science.sync.ProgressSyncManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * UserActivity with Google Sign-In integration.
 *
 * - Tap profile image to sign in (when signed out) or to sign out (when signed in).
 * - After sign-in we sync local xp/level to Firestore via ProgressSyncManager.
 * - Profile image is replaced with Google photo when available.
 *
 * Make sure you have:
 *  - google-services.json in app/
 *  - Firebase initialized in Application.onCreate (FirebaseApp.initializeApp)
 *  - strings for web_client_id and the various status messages
 */
class UserActivity : BaseActivity(), AchievementAdapter.OnAchievementClickListener {
    private val TAG = "UserActivity"

    private var achievementsList = ArrayList<Achievement>()
    private var mAdapter = AchievementAdapter(achievementsList, this, this)

    private lateinit var btnSignOut: Button
    private lateinit var tvUserInfo: TextView
    private lateinit var tvSyncStatus: TextView
    private lateinit var userImg: ImageView

    // Use lazy so FirebaseApp is initialized in Application.onCreate before we get the instance
    private val firebaseAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account?.idToken
            if (idToken != null) {
                tvSyncStatus.text = getString(R.string.signing_in)
                AuthManager.handleIdTokenForFirebase(idToken) { success, exception ->
                    runOnUiThread {
                        if (success) {
                            // update UI from Firebase currentUser and sync progress
                            updateUi()
                        } else {
                            tvSyncStatus.text = getString(R.string.sign_in_failed)
                            Log.w(TAG, "Firebase sign-in failed", exception)
                            Toast.makeText(this, R.string.sign_in_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                tvSyncStatus.text = getString(R.string.sign_in_failed)
                Toast.makeText(this, R.string.sign_in_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (e: ApiException) {
            tvSyncStatus.text = getString(R.string.sign_in_failed)
            Log.w(TAG, "Google sign-in failed", e)
            Toast.makeText(this, R.string.sign_in_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themePreference = ThemePreference(this)
        val themePrefValue = themePreference.getValue()
        if (themePrefValue == 100) {
            when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_NO -> setTheme(R.style.AppTheme)
                Configuration.UI_MODE_NIGHT_YES -> setTheme(R.style.AppThemeDark)
            }
        } else {
            setTheme(if (themePrefValue == 0) R.style.AppTheme else R.style.AppThemeDark)
        }
        setContentView(R.layout.activity_user)

        // UI wiring
        userImg = findViewById(R.id.user_img)
        tvUserInfo = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
        }
        tvSyncStatus = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
        }

        // insert status text under pro_badge if possible
        try {
            val proBadge = findViewById<TextView>(R.id.pro_badge)
            val parent = proBadge.parent as? ViewGroup
            parent?.let {
                val index = it.indexOfChild(proBadge)
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.gravity = Gravity.CENTER_HORIZONTAL
                it.addView(tvUserInfo, index + 1, params)
                val params2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params2.gravity = Gravity.CENTER_HORIZONTAL
                it.addView(tvSyncStatus, index + 2, params2)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add dynamic UI elements", e)
        }

        btnSignOut = Button(this).apply {
            text = getString(R.string.sign_out)
            visibility = View.GONE
        }
        try {
            val proBadge = findViewById<TextView>(R.id.pro_badge)
            val parent = proBadge.parent as? ViewGroup
            parent?.addView(btnSignOut)
        } catch (_: Exception) { /* ignore */ }

        // achievements list setup
        val recyclerView = findViewById<RecyclerView>(R.id.recycler_view_achievements)
        recyclerView.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        setupRecyclerView()

        findViewById<FrameLayout>(R.id.view_user).systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        setupTitleController()
        setupBackButton()
        setupStats()
        rateSetup()
        shareSetup()

        // PRO badge text
        val proPref = ProVersion(this).getValue()
        val proPlusPref = ProPlusVersion(this).getValue()
        if (proPref == 1) findViewById<TextView>(R.id.pro_badge).text = "NON-PRO"
        if (proPref == 100) findViewById<TextView>(R.id.pro_badge).text = "PRO-USER"
        if (proPlusPref == 100) findViewById<TextView>(R.id.pro_badge).text = "PRO+-USER"

        // user title persisted
        val sharedPref = getSharedPreferences("UserActivityPrefs", Context.MODE_PRIVATE)
        val userTitle = sharedPref.getString("user_title", "User Page")
        findViewById<TextView>(R.id.user_title_downstate).text = userTitle
        findViewById<TextView>(R.id.user_title_downstate).setOnClickListener {
            showEditTextPopup()
        }

        // legacy persisted image uri
        val uriString = sharedPref.getString("user_img_uri", null)
        uriString?.let {
            try {
                val uri = Uri.parse(it)
                userImg.setImageURI(uri)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set persisted user image uri", e)
            }
        }

        // profile image click: sign-in/out
        userImg.setOnClickListener {
            if (AuthManager.isSignedIn()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.sign_out)
                    .setMessage(R.string.confirm_sign_out)
                    .setPositiveButton(R.string.sign_out) { _, _ ->
                        AuthManager.signOut(null) {
                            runOnUiThread { updateUiForSignOut() }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                val webClientId = getString(R.string.web_client_id)
                val googleClient = AuthManager.buildGoogleSignInClient(this, webClientId)
                val signInIntent = googleClient.signInIntent
                signInLauncher.launch(signInIntent)
            }
        }

        btnSignOut.setOnClickListener {
            AuthManager.signOut(null) {
                runOnUiThread { updateUiForSignOut() }
            }
        }

        updateUi()
    }

    private fun updateUi() {
        val user = firebaseAuth.currentUser
        if (user != null) {
            tvUserInfo.text = getString(R.string.user_signed_in_fmt, user.displayName ?: user.email ?: "Unknown")
            btnSignOut.visibility = View.VISIBLE
            val photo = user.photoUrl
            if (photo != null) {
                loadImageFromUrlIntoImageView(photo.toString(), userImg)
            } else {
                userImg.setImageResource(R.drawable.ic_account)
            }
            // Trigger sync after sign-in
            onSigninSuccess()
        } else {
            updateUiForSignOut()
        }
    }

    private fun updateUiForSignOut() {
        tvUserInfo.text = getString(R.string.user_signed_out)
        tvSyncStatus.text = ""
        btnSignOut.visibility = View.GONE
        userImg.setImageResource(R.drawable.ic_account)
    }

    /**
     * Called after Firebase sign-in is established (or when UI is refreshed while signed in).
     * Reads FirebaseAuth.currentUser for uid/photo/name and performs progress sync.
     */
    private fun onSigninSuccess() {
        val uid = AuthManager.getUid()
        if (uid == null) {
            tvSyncStatus.text = getString(R.string.sign_in_failed)
            return
        }
        tvSyncStatus.text = getString(R.string.syncing_progress)
        ProgressSyncManager.mergeAndUploadLocalProgress(this, uid) { success ->
            runOnUiThread {
                tvSyncStatus.text = if (success) getString(R.string.sync_complete) else getString(R.string.sync_failed)
            }
        }
    }

    // minimal image loader using executor, avoids adding extra dependencies
    private fun loadImageFromUrlIntoImageView(urlString: String, imageView: ImageView) {
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.doInput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.connect()
                val input = conn.inputStream
                val bmp = BitmapFactory.decodeStream(input)
                runOnUiThread {
                    if (bmp != null) imageView.setImageBitmap(bmp)
                }
                input.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load profile image", t)
            }
        }
    }

    private fun setupTitleController() {
        findViewById<FrameLayout>(R.id.common_title_user_color).visibility = View.INVISIBLE
        findViewById<TextView>(R.id.user_title).visibility = View.INVISIBLE
        findViewById<FrameLayout>(R.id.common_title_back_user).elevation = resources.getDimension(R.dimen.zero_elevation)
        findViewById<ScrollView>(R.id.user_scroll).viewTreeObserver.addOnScrollChangedListener {
            if (findViewById<ScrollView>(R.id.user_scroll).scrollY > 150) {
                findViewById<FrameLayout>(R.id.common_title_user_color).visibility = View.VISIBLE
                findViewById<TextView>(R.id.user_title).visibility = View.VISIBLE
                findViewById<ImageView>(R.id.user_img).visibility = View.VISIBLE
                findViewById<FrameLayout>(R.id.common_title_back_user).elevation = resources.getDimension(R.dimen.one_elevation)
            } else {
                findViewById<FrameLayout>(R.id.common_title_user_color).visibility = View.INVISIBLE
                findViewById<TextView>(R.id.user_title).visibility = View.INVISIBLE
                findViewById<FrameLayout>(R.id.common_title_back_user).elevation = resources.getDimension(R.dimen.zero_elevation)
            }
        }
    }

    private fun setupBackButton() {
        findViewById<ImageButton>(R.id.back_btn).setOnClickListener {
            onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.recycler_view_achievements)
        AchievementModel.getList(this, achievementsList)
        achievementsList.sortByDescending { it.progress.toDouble() / it.maxProgress.toDouble() }
        recyclerView.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        val adapter = AchievementAdapter(achievementsList, this, this)
        recyclerView.adapter = adapter
        adapter.notifyDataSetChanged()
    }

    private fun showEditTextPopup() {
        val popupView = LayoutInflater.from(this).inflate(R.layout.popup_edit_text, null)
        val editText = popupView.findViewById<EditText>(R.id.editText)
        val buttonOk = popupView.findViewById<Button>(R.id.buttonOk)

        val alertDialog = AlertDialog.Builder(this)
            .setView(popupView)
            .create()

        buttonOk.setOnClickListener {
            val newText = editText.text.toString()
            findViewById<TextView>(R.id.user_title_downstate).text = newText
            saveUserTitle(newText)
            alertDialog.dismiss()
        }

        alertDialog.show()
    }

    private fun saveUserTitle(title: String) {
        val sharedPref = getSharedPreferences("UserActivityPrefs", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("user_title", title)
            apply()
        }
    }

    private fun setupStats() {
        val statistics = ArrayList<Statistics>()
        StatisticsModel.getList(this, statistics)
        if (statistics.size >= 3) {
            findViewById<TextView>(R.id.elements_stat).text = statistics[0].progress.toString()
            findViewById<TextView>(R.id.calculation_stat).text = statistics[1].progress.toString()
            findViewById<TextView>(R.id.search_stat).text = statistics[2].progress.toString()
        }
    }

    private fun rateSetup() {
        val manager = com.google.android.play.core.review.ReviewManagerFactory.create(this)
        val request = manager.requestReviewFlow()
        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val reviewInfo = task.result
                findViewById<TextView>(R.id.rate_btn).setOnClickListener {
                    val flow = manager.launchReviewFlow(this, reviewInfo)
                    flow.addOnCompleteListener {
                        Log.d(TAG, "In-app review flow finished.")
                    }
                }
            } else {
                val exception = task.exception
                if (exception != null) {
                    Log.w(TAG, "Request review flow failed", exception)
                } else {
                    Log.w(TAG, "Request review flow failed with unknown error")
                }
            }
        }
    }

    private fun shareSetup() {
        findViewById<TextView>(R.id.share_btn).setOnClickListener {
            val share = Intent.createChooser(Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, "'Atomic' - The open-source Periodic Table! Get it now via the following link: https://play.google.com/store/apps/details?id=com.jlindemann.science")
                type = "text/plain"
            }, null)
            startActivity(share)
        }
    }

    override fun onApplySystemInsets(top: Int, bottom: Int, left: Int, right: Int) {
        val params = findViewById<FrameLayout>(R.id.common_title_back_user).layoutParams as ViewGroup.LayoutParams
        params.height = top + resources.getDimensionPixelSize(R.dimen.title_bar)
        findViewById<FrameLayout>(R.id.common_title_back_user).layoutParams = params

        val params2 = findViewById<ImageView>(R.id.user_img).layoutParams as ViewGroup.MarginLayoutParams
        params2.topMargin = top + resources.getDimensionPixelSize(R.dimen.title_bar) + resources.getDimensionPixelSize(R.dimen.header_down_margin)
        findViewById<ImageView>(R.id.user_img).layoutParams = params2
    }

    override fun achievementClickListener(item: Achievement, position: Int) {
        // Not implemented yet
    }
}