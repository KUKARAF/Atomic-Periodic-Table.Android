package com.jlindemann.science.activities.tools

import GameResultItem
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.*
import androidx.core.widget.NestedScrollView
import com.jlindemann.science.R
import com.jlindemann.science.activities.BaseActivity
import com.jlindemann.science.util.LivesManager
import com.jlindemann.science.util.XpManager
import java.util.concurrent.TimeUnit
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.Gravity
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.PopupWindow
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.AppCompatButton
import com.jlindemann.science.activities.UserActivity
import com.jlindemann.science.activities.settings.ProActivity
import com.jlindemann.science.activities.settings.SubmitActivity
import com.jlindemann.science.preferences.MostUsedToolPreference
import com.jlindemann.science.preferences.ProPlusVersion
import com.jlindemann.science.preferences.ProVersion

class FlashCardActivity : BaseActivity() {

    private lateinit var toggles: List<ToggleButton>
    private lateinit var infoText: TextView
    private var resultDialog: ResultDialogFragment? = null
    private var lastLevel: Int = -1

    // Back gesture handling
    private var backCallback: OnBackPressedCallback? = null
    private var onBackInvokedCb: android.window.OnBackInvokedCallback? = null

    // Keep reference to lives info popup so we can dismiss it on back
    private var livesPopupWindow: PopupWindow? = null
    private var livesPopupView: View? = null

    // Polling for dynamic lives updates while activity is visible
    private val uiHandler = Handler(Looper.getMainLooper())
    private var cachedLives: Int = -1
    private var livesPolling = false
    private val LIVES_POLL_INTERVAL_MS = 1000L // poll every second

    private val livesPollRunnable = object : Runnable {
        override fun run() {
            // Attempt to apply any pending refills first
            val refilled = LivesManager.refillLivesIfNeeded(this@FlashCardActivity)
            // Get current lives after refill attempt
            val currentLives = LivesManager.getLives(this@FlashCardActivity)
            val livesChanged = currentLives != cachedLives

            // Always update the countdown/info text so timer ticks down every second,
            // but only update count/boxes when the numeric value changed.
            if (livesChanged || refilled) {
                cachedLives = currentLives
                updateLivesCount()
                updateCategoryBoxes()
            }
            // updateinfo (countdown text) every poll so user sees timer tick
            updateLivesInfo()

            // Update popup contents in-place if popup visible
            if (livesPopupWindow?.isShowing == true && livesPopupView != null) {
                updateLivesPopupView(livesPopupView!!)
            }

            // Schedule next poll if still enabled
            if (livesPolling) {
                uiHandler.postDelayed(this, LIVES_POLL_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themePreference = com.jlindemann.science.preferences.ThemePreference(this)
        val themePrefValue = themePreference.getValue()

        if (themePrefValue == 100) {
            when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_NO -> setTheme(R.style.AppTheme)
                Configuration.UI_MODE_NIGHT_YES -> setTheme(R.style.AppThemeDark)
            }
        }
        if (themePrefValue == 0) setTheme(R.style.AppTheme)
        if (themePrefValue == 1) setTheme(R.style.AppThemeDark)

        setContentView(R.layout.activity_flashcards)
        findViewById<FrameLayout>(R.id.view_flash).systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        // Title Controller
        findViewById<FrameLayout>(R.id.common_title_back_fla_color).visibility = View.INVISIBLE
        findViewById<TextView>(R.id.flashcard_title).visibility = View.INVISIBLE
        findViewById<FrameLayout>(R.id.common_title_back_fla).elevation = (resources.getDimension(R.dimen.zero_elevation))
        val scrollView = findViewById<NestedScrollView>(R.id.flashcard_scroll)
        scrollView?.viewTreeObserver?.addOnScrollChangedListener(object : ViewTreeObserver.OnScrollChangedListener {
            private var isTitleVisible = false // Track animation state

            override fun onScrollChanged() {
                val scrollY = scrollView.scrollY
                val threshold = 150

                val titleColorBackground = findViewById<FrameLayout>(R.id.common_title_back_fla_color)
                val titleText = findViewById<TextView>(R.id.flashcard_title)
                val titleDownstateText = findViewById<TextView>(R.id.flashcard_title_downstate)
                val titleBackground = findViewById<FrameLayout>(R.id.common_title_back_fla)

                if (scrollY > threshold) {
                    if (!isTitleVisible) {
                        TitleBarAnimator.animateVisibility(titleColorBackground, true, visibleAlpha = 0.11f)
                        TitleBarAnimator.animateVisibility(titleText, true)
                        TitleBarAnimator.animateVisibility(titleDownstateText, false)
                        titleBackground.elevation = resources.getDimension(R.dimen.one_elevation)
                        isTitleVisible = true
                    }
                } else {
                    if (isTitleVisible) {
                        TitleBarAnimator.animateVisibility(titleColorBackground, false)
                        TitleBarAnimator.animateVisibility(titleText, false)
                        TitleBarAnimator.animateVisibility(titleDownstateText, true)
                        titleBackground.elevation = resources.getDimension(R.dimen.zero_elevation)
                        isTitleVisible = false
                    }
                }
            }
        })

        findViewById<ImageButton>(R.id.back_btn_fla).setOnClickListener {
            onBackPressed()
        }

        findViewById<ImageButton>(R.id.achievements_btn).setOnClickListener {
            val intent = Intent(this, UserActivity::class.java)
            startActivity(intent)
        }

        //Add value to most used:
        val mostUsedPreference = MostUsedToolPreference(this)
        val mostUsedPrefValue = mostUsedPreference.getValue()
        val targetLabel = "fla"
        val regex = Regex("($targetLabel)=(\\d\\.\\d)")
        val match = regex.find(mostUsedPrefValue)
        if (match != null) {
            val value = match.groups[2]!!.value.toDouble()
            val newValue = value + 1
            mostUsedPreference.setValue(mostUsedPrefValue.replace("$targetLabel=$value", "$targetLabel=$newValue"))
        }

        infoText = findViewById(R.id.tv_lives_info)
        setupDifficultyToggles()
        setCategoryListeners()
        findViewById<ImageButton>(R.id.shuffle_btn).setOnClickListener {
            launchRandomUnlockedGame()
        }
        //PRO Changes
        val proPlusPref = ProPlusVersion(this)
        var proPlusPrefValue = proPlusPref.getValue()
        if (proPlusPrefValue==100) {
            findViewById<FrameLayout>(R.id.pro_box).visibility = View.GONE
        }
        else {
            findViewById<TextView>(R.id.get_pro_plus_btn).setOnClickListener {
                val intent = Intent(this, ProActivity::class.java)
                startActivity(intent)
            }
        }

        // Lives popup: add click listener to lives count in toolbar
        val livesCountView = findViewById<TextView>(R.id.tv_lives_count)
        livesCountView.setOnClickListener {
            showLivesInfoPopup(livesCountView)
        }

        // Register lifecycle-aware OnBackPressedCallback in DISABLED state.
        // We'll enable interception only when an overlay (result dialog or lives popup) is visible.
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                val consumed = handleBackPress()
                backCallback?.isEnabled = anyOverlayOpen()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback!!)

        // Ensure we are aware when the result dialog is destroyed so interception is cleared.
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentDestroyed(fm: androidx.fragment.app.FragmentManager, f: androidx.fragment.app.Fragment) {
                super.onFragmentDestroyed(fm, f)
                if (f is ResultDialogFragment) {
                    // result dialog dismissed -> update interception
                    setBackInterceptionEnabled(anyOverlayOpen())
                }
            }
        }, true)
    }

    //For updating when user purschases PRO Version in ProActivity
    private fun setProFabVisibilityGoneIfProValue100() {
        val proPlusPref = ProPlusVersion(this)
        val value = proPlusPref.getValue()
        if (value == 100) {
            findViewById<FrameLayout>(R.id.pro_box).visibility = View.GONE
        } else {
            findViewById<FrameLayout>(R.id.pro_box).visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure stored lives are applied on resume immediately
        LivesManager.refillLivesIfNeeded(this)

        // initialize cached value and update UI once
        cachedLives = LivesManager.getLives(this)
        updateLivesCount()
        updateLivesInfo()
        updateCategoryBoxes()
        updateXpAndLevelStats()
        setProFabVisibilityGoneIfProValue100()
        val gameFinished = intent.getBooleanExtra("game_finished", false)
        val results = intent.getParcelableArrayListExtra<GameResultItem>("game_results")
        val totalQuestions = intent.getIntExtra("total_questions", results?.size ?: 0)
        val difficulty = intent.getStringExtra("difficulty") ?: "easy"
        if (results != null && results.isNotEmpty()) {
            showGameResultsPopup(results, gameFinished, totalQuestions, difficulty)
            intent.removeExtra("game_finished")
            intent.removeExtra("game_results")
            intent.removeExtra("total_questions")
            intent.removeExtra("difficulty")
        }
        showLevelUpPopupIfNeeded()

        // Start live polls so UI updates dynamically
        startLivesPolling()
    }

    override fun onPause() {
        super.onPause()
        // stop polling while activity is not in foreground
        stopLivesPolling()
    }

    private fun startLivesPolling() {
        if (livesPolling) return
        livesPolling = true
        uiHandler.post(livesPollRunnable)
    }

    private fun stopLivesPolling() {
        livesPolling = false
        uiHandler.removeCallbacks(livesPollRunnable)
    }

    private fun showLevelUpPopupIfNeeded() {
        val xp = XpManager.getXp(this)
        val currentLevel = XpManager.getLevel(xp)
        if (lastLevel != -1 && currentLevel > lastLevel) {
            AlertDialog.Builder(this)
                .setTitle("Level Up!")
                .setMessage("Congratulations, you've reached level $currentLevel!")
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .setCancelable(true)
                .show()
        }
        lastLevel = currentLevel
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val gameFinished = intent.getBooleanExtra("game_finished", false)
        val results = intent.getParcelableArrayListExtra<GameResultItem>("game_results")
        val totalQuestions = intent.getIntExtra("total_questions", results?.size ?: 0)
        val difficulty = intent.getStringExtra("difficulty") ?: "easy"
        if (results != null && results.isNotEmpty()) {
            showGameResultsPopup(results, gameFinished, totalQuestions, difficulty)
        }
    }

    private fun setupDifficultyToggles() {
        toggles = listOf(
            findViewById(R.id.toggle_easy),
            findViewById(R.id.toggle_medium),
            findViewById(R.id.toggle_hard)
        )
        toggles.forEach { toggle ->
            toggle.setOnCheckedChangeListener { btn, isChecked ->
                if (isChecked) {
                    toggles.filter { it != btn }.forEach { it.isChecked = false }
                }
            }
        }
        toggles[0].isChecked = true
    }

    private fun getSelectedDifficulty(): String {
        return when {
            toggles[0].isChecked -> "easy"
            toggles[1].isChecked -> "medium"
            toggles[2].isChecked -> "hard"
            else -> "easy"
        }
    }

    private fun setCategoryListeners() {
        val proCategoryIds = setOf(
            R.id.btn_discovered_by, R.id.btn_discovery_year, R.id.btn_electrical_type,
            R.id.btn_radioactive, R.id.btn_electronegativity, R.id.btn_block,
            R.id.btn_crystal_structure, R.id.btn_superconducting_point
        )
        val categories = mapOf(
            R.id.btn_element_symbols to "element_symbols",
            R.id.btn_element_names to "element_names",
            R.id.btn_element_classifications to "element_classifications",
            R.id.btn_appearance to "appearance",
            R.id.btn_atomic_number to "atomic_number",
            R.id.btn_atomic_mass to "atomic_mass",
            R.id.btn_density to "density",
            R.id.btn_magnetic_type to "magnetic_type",
            R.id.btn_phase_stp to "phase_stp",
            R.id.btn_neutron_cross_sectional to "neutron_cross_sectional",
            R.id.btn_specific_heat_capacity to "specific_heat_capacity",
            R.id.btn_boiling_point_kelvin to "element_boiling_kelvin",
            R.id.btn_boiling_point_celsius to "element_boiling_celsius",
            R.id.btn_boiling_point_fahrenheit to "element_boiling_fahrenheit",
                    // Pro user categories:
            R.id.btn_discovered_by to "discovered_by",
            R.id.btn_discovery_year to "discovery_year",
            R.id.btn_electrical_type to "electrical_type",
            R.id.btn_radioactive to "radioactive",
            R.id.btn_electronegativity to "electronegativity",
            R.id.btn_block to "block",
            R.id.btn_crystal_structure to "crystal_structure",
            R.id.btn_superconducting_point to "superconducting_point",
            R.id.btn_mohs_hardness to "mohs_hardness",
            R.id.btn_vickers_hardness to "vickers_hardness",
            R.id.btn_brinell_hardness to "brinell_hardness",
            R.id.btn_melting_point_kelvin to "element_melting_kelvin",
            R.id.btn_melting_point_celsius to "element_melting_celsius",
            R.id.btn_melting_point_fahrenheit to "element_melting_fahrenheit"
        )
        categories.forEach { (btnId, category) ->
            val btn = findViewById<View>(btnId)
            btn.setOnClickListener {
                val isPro = checkProPlusStatus()
                val isProCategory = proCategoryIds.contains(btnId)
                val canPlayGame = btn.isEnabled && (!isProCategory || isPro)

                if (isProCategory && !isPro) {
                    // Pro required, always send to ProActivity even if greyed out
                    startActivity(Intent(this, ProActivity::class.java))
                } else if (canPlayGame) {
                    // Start the game if enabled and (not pro category or is pro)
                    val intent = Intent(this, LearningGamesActivity::class.java)
                    intent.putExtra("difficulty", getSelectedDifficulty())
                    intent.putExtra("category", category)
                    startActivity(intent)
                }
                // else: do nothing if not enabled and not pro category (greyed out due to lives/level)
            }
        }
    }

    // Helper to set lock icon on section header
    private fun TextView.setLockDrawable(unlocked: Boolean) {
        val drawable = if (unlocked) R.drawable.ic_lock_open else R.drawable.ic_lock
        setCompoundDrawablesWithIntrinsicBounds(drawable, 0, 0, 0)
    }

    private fun updateCategoryBoxes() {
        val xp = XpManager.getXp(this)
        val userLevel = XpManager.getLevel(xp)
        val isProUser = checkProPlusStatus()
        val lives = LivesManager.getLives(this)
        val isEnabled = lives > 0

        val proCategoryButtons = listOf(
            Pair(R.id.btn_discovered_by, R.id.pro_badge_discovered_by),
            Pair(R.id.btn_discovery_year, R.id.pro_badge_discovery_year),
            Pair(R.id.btn_electrical_type, R.id.pro_badge_electrical_type),
            Pair(R.id.btn_radioactive, R.id.pro_badge_radioactive),
            Pair(R.id.btn_electronegativity, R.id.pro_badge_electronegativity),
            Pair(R.id.btn_block, R.id.pro_badge_block),
            Pair(R.id.btn_crystal_structure, R.id.pro_badge_crystal_structure),
            Pair(R.id.btn_superconducting_point, R.id.pro_badge_superconducting_point),
            Pair(R.id.btn_mohs_hardness, R.id.pro_badge_mohs_hardness),
            Pair(R.id.btn_vickers_hardness, R.id.pro_badge_vickers_hardness),
            Pair(R.id.btn_brinell_hardness, R.id.pro_badge_brinell_hardness),
            Pair(R.id.btn_melting_point_kelvin, R.id.pro_badge_melting_point_kelvin),
            Pair(R.id.btn_melting_point_celsius, R.id.pro_badge_melting_point_celsius),
            Pair(R.id.btn_melting_point_fahrenheit, R.id.pro_badge_melting_point_fahrenheit),
        )

        for ((btnId, badgeId) in proCategoryButtons) {
            val badge = findViewById<TextView>(badgeId)
            badge?.visibility = if (isProUser) View.GONE else View.VISIBLE
        }

        val proButtonsByBox = mapOf(
            R.id.box_0_4 to listOf(R.id.btn_discovered_by, R.id.btn_discovery_year),
            R.id.box_5_9 to listOf(R.id.btn_electrical_type, R.id.btn_radioactive),
            R.id.box_10_14 to listOf(R.id.btn_electronegativity, R.id.btn_block),
            R.id.box_15_19 to listOf(R.id.btn_crystal_structure, R.id.btn_superconducting_point),
            R.id.box_20_24 to listOf(R.id.btn_mohs_hardness, R.id.btn_vickers_hardness, R.id.btn_brinell_hardness),
            R.id.box_25_29 to listOf(R.id.btn_melting_point_kelvin, R.id.btn_melting_point_celsius, R.id.btn_melting_point_fahrenheit)
        )
        val boxesWithLevels = listOf(
            Triple(R.id.box_0_4, R.id.title_box_0_4, 0..4),
            Triple(R.id.box_5_9, R.id.title_box_5_9, 5..9),
            Triple(R.id.box_10_14, R.id.title_box_10_14, 10..14),
            Triple(R.id.box_15_19, R.id.title_box_15_19, 15..19),
            Triple(R.id.box_20_24, R.id.title_box_20_24, 20..24),
            Triple(R.id.box_25_29, R.id.title_box_25_29, 25..29)
        )
        val proCategoryIds = setOf(
            R.id.btn_discovered_by, R.id.btn_discovery_year, R.id.btn_electrical_type,
            R.id.btn_radioactive, R.id.btn_electronegativity, R.id.btn_block,
            R.id.btn_crystal_structure, R.id.btn_superconducting_point,
            R.id.btn_mohs_hardness, R.id.btn_vickers_hardness, R.id.btn_brinell_hardness,
            R.id.btn_melting_point_kelvin, R.id.btn_melting_point_celsius, R.id.btn_melting_point_fahrenheit
        )

        for ((boxId, titleId, levelRange) in boxesWithLevels) {
            val box = findViewById<View>(boxId)
            val title = findViewById<TextView>(titleId)
            val unlocked = userLevel >= levelRange.last

            // If the userLevel is within the range, it's the "current" box, so also unlocked
            val current = userLevel in levelRange
            val boxIsUnlocked = unlocked || current

            box.alpha = if (boxIsUnlocked) 1f else 0.5f
            title.setLockDrawable(boxIsUnlocked)

            // Set enabled/alpha for each button in this box
            for (i in 0 until (box as ViewGroup).childCount) {
                val child = box.getChildAt(i)
                if (child.id == titleId) continue // skip the header
                val isProBtn = proCategoryIds.contains(child.id)
                child.isEnabled = boxIsUnlocked && isEnabled && (!isProBtn || isProUser)
                child.alpha = if (child.isEnabled) 1f else 0.5f
            }
        }
    }

    private fun checkProPlusStatus(): Boolean {
        val proPlusPref = ProPlusVersion(this)
        val proPlusPrefValue = proPlusPref.getValue()
        return proPlusPrefValue == 100
    }

    private fun updateLivesInfo() {
        val lives = LivesManager.getLives(this)
        if (lives == 0) {
            infoText.visibility = View.VISIBLE
            val millis = LivesManager.getMillisToRefill(this)
            val hours = TimeUnit.MILLISECONDS.toHours(millis)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
            val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
            infoText.text = "Out of lives! More lives in $minutes minutes and $seconds seconds."
        } else {
            infoText.visibility = View.GONE
        }
    }

    private fun updateXpAndLevelStats() {
        val xp = XpManager.getXp(this)
        val level = XpManager.getLevel(xp)
        val minXp = XpManager.getXpForLevel(level)
        val maxXp = XpManager.getXpForLevel(level + 1)
        val xpInLevel = xp - minXp
        val xpRequired = maxXp - minXp
        val completed = getCompletedQuizzes()

        findViewById<TextView>(R.id.completed_quizzes_stat).text = completed.toString()
        findViewById<TextView>(R.id.total_xp_stat).text = "Total XP: $xp"
        findViewById<TextView>(R.id.level_stat).text = level.toString()
        findViewById<ProgressBar>(R.id.xp_progress).apply {
            max = xpRequired
            progress = xpInLevel
        }
        findViewById<TextView>(R.id.progress_text).text = "$xpInLevel/$xpRequired"
    }

    override fun onBackPressed() {
        // Try to handle overlays first; if not consumed, allow system/back behaviour
        if (!handleBackPress()) {
            super.onBackPressed()
        }
    }

    private fun showGameResultsPopup(
        results: List<GameResultItem>,
        gameFinished: Boolean,
        totalQuestions: Int,
        difficulty: String = "easy"
    ) {
        if (resultDialog?.isVisible == true) return
        resultDialog = ResultDialogFragment.newInstance(results, gameFinished, totalQuestions, difficulty)
        resultDialog?.show(supportFragmentManager, "GameResultsPopup")
        updateXpAndLevelStats()

        // result dialog shown -> intercept back gestures while it's visible
        setBackInterceptionEnabled(true)
    }

    private fun getCompletedQuizzes(): Int {
        val prefs = getSharedPreferences("game_stats", MODE_PRIVATE)
        return prefs.getInt("completed_quizzes", 0)
    }

    private fun incrementCompletedQuizzes() {
        val prefs = getSharedPreferences("game_stats", MODE_PRIVATE)
        val current = prefs.getInt("completed_quizzes", 0)
        prefs.edit().putInt("completed_quizzes", current + 1).apply()
    }

    // Helper extension function for animating visibility
    fun View.animateVisibility(
        setVisible: Boolean,
        duration: Long = 200,
        visibleAlpha: Float = 1.0f
    ) {
        if (setVisible) {
            alpha = 0f
            visibility = View.VISIBLE
            animate()
                .alpha(visibleAlpha)
                .setDuration(duration)
                .setListener(null)
        } else {
            animate()
                .alpha(0f)
                .setDuration(duration)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        visibility = View.INVISIBLE
                    }
                })
        }
    }

    private fun getAllUnlockedCategoryButtons(): List<Pair<View, String>> {
        val proCategoryIds = setOf(
            R.id.btn_discovered_by, R.id.btn_discovery_year, R.id.btn_electrical_type,
            R.id.btn_radioactive, R.id.btn_electronegativity, R.id.btn_block,
            R.id.btn_crystal_structure, R.id.btn_superconducting_point,
            R.id.btn_mohs_hardness, R.id.btn_vickers_hardness, R.id.btn_brinell_hardness,
            R.id.btn_melting_point_kelvin, R.id.btn_melting_point_celsius, R.id.btn_melting_point_fahrenheit
        )
        val categories = mapOf(
            R.id.btn_element_symbols to "element_symbols",
            R.id.btn_element_names to "element_names",
            R.id.btn_element_classifications to "element_classifications",
            R.id.btn_appearance to "appearance",
            R.id.btn_atomic_number to "atomic_number",
            R.id.btn_atomic_mass to "atomic_mass",
            R.id.btn_density to "density",
            R.id.btn_magnetic_type to "magnetic_type",
            R.id.btn_phase_stp to "phase_stp",
            R.id.btn_neutron_cross_sectional to "neutron_cross_sectional",
            R.id.btn_specific_heat_capacity to "specific_heat_capacity",
            R.id.btn_boiling_point_kelvin to "element_boiling_kelvin",
            R.id.btn_boiling_point_celsius to "element_boiling_celsius",
            R.id.btn_boiling_point_fahrenheit to "element_boiling_fahrenheit",
            // Pro user categories:
            R.id.btn_discovered_by to "discovered_by",
            R.id.btn_discovery_year to "discovery_year",
            R.id.btn_electrical_type to "electrical_type",
            R.id.btn_radioactive to "radioactive",
            R.id.btn_electronegativity to "electronegativity",
            R.id.btn_block to "block",
            R.id.btn_crystal_structure to "crystal_structure",
            R.id.btn_superconducting_point to "superconducting_point",
            R.id.btn_mohs_hardness to "mohs_hardness",
            R.id.btn_vickers_hardness to "vickers_hardness",
            R.id.btn_brinell_hardness to "brinell_hardness",
            R.id.btn_melting_point_kelvin to "element_melting_kelvin",
            R.id.btn_melting_point_celsius to "element_melting_celsius",
            R.id.btn_melting_point_fahrenheit to "element_melting_fahrenheit"
        )
        val isPro = checkProPlusStatus()
        val unlocked = mutableListOf<Pair<View, String>>()
        for ((btnId, category) in categories) {
            val btn = findViewById<View>(btnId)
            val isProCategory = proCategoryIds.contains(btnId)
            if (btn != null && btn.isEnabled && (!isProCategory || isPro)) {
                unlocked.add(btn to category)
            }
        }
        return unlocked
    }

    private fun launchRandomUnlockedGame() {
        val unlocked = getAllUnlockedCategoryButtons()
        if (unlocked.isEmpty()) {
            Toast.makeText(this, "No unlocked games available!", Toast.LENGTH_SHORT).show()
            return
        }
        val (btn, category) = unlocked.random()
        // Use currently selected difficulty
        val difficulty = getSelectedDifficulty()
        val isProCategory = setOf(
            R.id.btn_discovered_by, R.id.btn_discovery_year, R.id.btn_electrical_type,
            R.id.btn_radioactive, R.id.btn_electronegativity, R.id.btn_block,
            R.id.btn_crystal_structure, R.id.btn_superconducting_point,
            R.id.btn_mohs_hardness, R.id.btn_vickers_hardness, R.id.btn_brinell_hardness,
            R.id.btn_melting_point_kelvin, R.id.btn_melting_point_celsius, R.id.btn_melting_point_fahrenheit
        ).contains(btn.id)
        if (isProCategory && !checkProPlusStatus()) {
            startActivity(Intent(this, ProActivity::class.java))
            return
        }
        val intent = Intent(this, LearningGamesActivity::class.java)
        intent.putExtra("difficulty", difficulty)
        intent.putExtra("category", category)
        startActivity(intent)
    }

    override fun onApplySystemInsets(top: Int, bottom: Int, left: Int, right: Int) {
        val params = findViewById<FrameLayout>(R.id.common_title_back_fla).layoutParams as ViewGroup.LayoutParams
        params.height = top + resources.getDimensionPixelSize(R.dimen.title_bar)
        findViewById<FrameLayout>(R.id.common_title_back_fla).layoutParams = params

        val params2 = findViewById<TextView>(R.id.flashcard_title_downstate).layoutParams as ViewGroup.MarginLayoutParams
        params2.topMargin = top + resources.getDimensionPixelSize(R.dimen.title_bar) +
                resources.getDimensionPixelSize(R.dimen.header_down_margin)
        findViewById<TextView>(R.id.flashcard_title_downstate).layoutParams = params2
    }

    private fun showLivesInfoPopup(anchor: View) {
        val inflater = LayoutInflater.from(this)
        val popupView = inflater.inflate(R.layout.popup_lives_info, null)

        // Ensure we apply any pending refills before showing UI
        LivesManager.refillLivesIfNeeded(this)

        // Save popupView reference so we can update it while visible
        livesPopupView = popupView

        // Initialize popup contents
        updateLivesPopupView(popupView)

        // Close any existing popup first
        livesPopupWindow?.dismiss()

        val popupWindow = PopupWindow(
            popupView,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.elevation = 8f
        livesPopupWindow = popupWindow

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        popupWindow.showAtLocation(anchor, Gravity.NO_GRAVITY, location[0], location[1] + anchor.height)

        // When popup is showing we should intercept back gestures so the popup can be closed first.
        setBackInterceptionEnabled(true)

        popupWindow.setOnDismissListener {
            // update interception state when popup dismissed
            setBackInterceptionEnabled(anyOverlayOpen())
            livesPopupWindow = null
            livesPopupView = null
        }

        // Auto-dismiss after 3s
        Handler(Looper.getMainLooper()).postDelayed({
            if (popupWindow.isShowing) popupWindow.dismiss()
        }, 3000)
    }

    private fun updateLivesPopupView(popupView: View) {
        val lives = LivesManager.getLives(this)
        val millis = LivesManager.getMillisToRefill(this)
        val maxLives = LivesManager.getMaxLives(this)
        val refillAmount = LivesManager.getRefillAmount(this)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60

        val livesText = popupView.findViewById<TextView>(R.id.lives_info_text)
        if (lives >= maxLives) {
            livesText.text = "You have full lives!"
        } else {
            livesText.text = "Next life in $minutes minutes and $seconds seconds.\nYou will gain $refillAmount life${if (refillAmount > 1) "s" else ""}."
        }
    }

    // Helper: returns true if any overlay that should intercept back is visible
    private fun anyOverlayOpen(): Boolean {
        val dialogVisible = resultDialog?.isVisible == true
        val popupVisible = livesPopupWindow?.isShowing == true
        return dialogVisible || popupVisible
    }

    // Centralized enabling/disabling of back interception; also registers/unregisters platform callback on newer OS.
    private fun setBackInterceptionEnabled(enabled: Boolean) {
        backCallback?.isEnabled = enabled

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (enabled) {
                if (onBackInvokedCb == null) {
                    onBackInvokedCb = android.window.OnBackInvokedCallback {
                        // Mirror the OnBackPressedCallback behavior
                        handleBackPress()
                        // keep registered only if overlays remain
                        if (!anyOverlayOpen()) {
                            try {
                                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(onBackInvokedCb!!)
                            } catch (_: Exception) { }
                            onBackInvokedCb = null
                            backCallback?.isEnabled = false
                        }
                    }
                    onBackInvokedDispatcher.registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        onBackInvokedCb!!
                    )
                }
            } else {
                if (onBackInvokedCb != null) {
                    try {
                        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(onBackInvokedCb!!)
                    } catch (_: Exception) { }
                    onBackInvokedCb = null
                }
            }
        }
    }

    // Close overlays (result dialog or lives popup) if visible; return true when consumed
    private fun handleBackPress(): Boolean {
        // If result dialog visible, dismiss it
        if (resultDialog?.isVisible == true) {
            resultDialog?.dismiss()
            // update interception state after dismissal
            setBackInterceptionEnabled(anyOverlayOpen())
            return true
        }

        // If lives popup visible, dismiss it
        if (livesPopupWindow?.isShowing == true) {
            livesPopupWindow?.dismiss()
            // setBackInterceptionEnabled will be called by popup's dismiss listener, but do it here too
            setBackInterceptionEnabled(anyOverlayOpen())
            return true
        }

        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure cleanup of back interception hooks
        backCallback?.remove()
        backCallback = null
        if (onBackInvokedCb != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(onBackInvokedCb!!)
            } catch (_: Exception) { }
            onBackInvokedCb = null
        }
        // Dismiss popup if still present
        livesPopupWindow?.dismiss()
        livesPopupWindow = null

        // stop polling
        stopLivesPolling()
    }
}