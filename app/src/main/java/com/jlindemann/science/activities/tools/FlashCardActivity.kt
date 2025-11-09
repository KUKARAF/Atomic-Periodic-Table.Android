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
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.Gravity
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
import com.jlindemann.science.utils.StreakManager

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

    // Data model for creating boxes dynamically
    private data class CategorySpec(val key: String, val labelRes: Int, val isPro: Boolean = false)
    private data class LevelBoxSpec(val range: IntRange, val categories: List<CategorySpec>)

    // Keep runtime references to created category rows so we can control enabled state & random-launch
    private val createdCategoryRows = mutableListOf<Pair<View, String>>() // view -> categoryKey

    // Reward levels for 5% XP bonus (automatically applied when level is reached)
    private val REWARD_LEVELS = listOf(10, 15, 20)

    // Define the boxes and categories (keeps same logical contents as previous XML)
    private val levelBoxesSpec = listOf(
        LevelBoxSpec(0..4, listOf(
            CategorySpec("element_symbols", R.string.element_symbols),
            CategorySpec("element_names", R.string.element_names),
            CategorySpec("element_classifications", R.string.element_groups),
            CategorySpec("discovered_by", R.string.discovered_by, isPro = true),
            CategorySpec("discovery_year", R.string.discovery_year, isPro = true)
        )),
        LevelBoxSpec(5..9, listOf(
            CategorySpec("appearance", R.string.appearance),
            CategorySpec("atomic_number", R.string.atomic_number),
            CategorySpec("electrical_type", R.string.electrical_type, isPro = true),
            CategorySpec("radioactive", R.string.radioactive, isPro = true)
        )),
        LevelBoxSpec(10..14, listOf(
            CategorySpec("atomic_mass", R.string.atomic_mass),
            CategorySpec("density", R.string.density),
            CategorySpec("electronegativity", R.string.electronegativity, isPro = true),
            CategorySpec("block", R.string.block, isPro = true)
        )),
        LevelBoxSpec(15..19, listOf(
            CategorySpec("magnetic_type", R.string.magnetic_type),
            CategorySpec("phase_stp", R.string.phase_stp),
            CategorySpec("crystal_structure", R.string.crystal_structure, isPro = true),
            CategorySpec("superconducting_point", R.string.superconducting_point, isPro = true)
        )),
        LevelBoxSpec(20..24, listOf(
            CategorySpec("neutron_cross_sectional", R.string.neutron_cross_sectional),
            CategorySpec("specific_heat_capacity", R.string.specific_heat_capacity),
            CategorySpec("mohs_hardness", R.string.mohs_hardness, isPro = true),
            CategorySpec("vickers_hardness", R.string.vickers_hardness, isPro = true),
            CategorySpec("brinell_hardness", R.string.brinell_hardness, isPro = true)
        )),
        LevelBoxSpec(25..29, listOf(
            CategorySpec("element_boiling_celsius", R.string.boiling_point_celsius),
            CategorySpec("element_boiling_fahrenheit", R.string.boiling_point_fahrenheit),
            CategorySpec("element_boiling_kelvin", R.string.boiling_point_kelvin),
            CategorySpec("element_melting_celsius", R.string.melting_point_celsius, isPro = true),
            CategorySpec("element_melting_fahrenheit", R.string.melting_point_fahrenheit, isPro = true),
            CategorySpec("element_melting_kelvin", R.string.melting_point_kelvin, isPro = true)
        )),
        LevelBoxSpec(30..34, listOf(
            CategorySpec("earth_crust", R.string.abundance_earth_crust),
            CategorySpec("earth_soils", R.string.abundance_earth_soils, isPro = true)
        ))
    )

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

        // Title Controller and other top UI wiring (unchanged)
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

        val achievementsBtn = findViewById<ImageButton>(R.id.achievements_btn)
        achievementsBtn.setOnClickListener {
            val intent = Intent(this, UserActivity::class.java)
            startActivity(intent)
        }

        // Add streak badge (programmatically) to the tools_layout to the right of the achievements button
        try {
            val toolsLayout = findViewById<LinearLayout>(R.id.tools_layout)
            val livesTextView = findViewById<TextView>(R.id.tv_lives_count)
            // create badge
            val streakView = TextView(this).apply {
                id = resources.getIdentifier("streak_count_text", "id", packageName).takeIf { it != 0 }
                    ?: View.generateViewId()

                // size 48dp -> px
                val sizePx = (48 * resources.displayMetrics.density).toInt()

                // padding (keeps small inner padding so text centers nicely inside the 48dp badge)
                val padH = resources.getDimensionPixelSize(R.dimen.padding_small)
                val padV = resources.getDimensionPixelSize(R.dimen.padding_tiny)
                setPadding(padH, padV, padH, padV)

                setTextColor(resources.getColor(android.R.color.white, theme))
                textSize = 12f
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.sunny)

                visibility = View.GONE

                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)

                // make it clickable to open achievements/user screen
                setOnClickListener {
                    startActivity(Intent(this@FlashCardActivity, UserActivity::class.java))
                }
            }
            // Insert just before the lives count so it appears to the right of achievements_btn
            val insertIndex = if (toolsLayout.indexOfChild(livesTextView) >= 0) toolsLayout.indexOfChild(livesTextView) else toolsLayout.childCount
            toolsLayout.addView(streakView, insertIndex)
        } catch (_: Exception) {
            // ignore if layout doesn't match; we still function without badge
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

        // Instead of static category listeners, we'll dynamically build the boxes and wire click listeners
        buildLevelBoxes()

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
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                val consumed = handleBackPress()
                backCallback?.isEnabled = anyOverlayOpen()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback!!)

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

    // Build level boxes dynamically from levelBoxesSpec and add to the boxes_container
    private fun buildLevelBoxes() {
        val container = findViewById<LinearLayout>(R.id.boxes_container)
        container.removeAllViews()
        createdCategoryRows.clear()

        val inflater = LayoutInflater.from(this)
        // detect pro status once so we can indicate disabled state for level 10 if needed
        val isProUser = checkProPlusStatus()
        for (boxSpec in levelBoxesSpec) {
            val boxView = inflater.inflate(R.layout.level_box, container, false)
            val titleView = boxView.findViewById<TextView>(R.id.level_box_title)
            val categoriesContainer = boxView.findViewById<LinearLayout>(R.id.level_categories_container)
            val rewardsContainer = boxView.findViewById<LinearLayout>(R.id.level_rewards_container)
            val rewardsButtons = boxView.findViewById<LinearLayout>(R.id.level_rewards_buttons)

            titleView.text = getString(R.string.level_range_format, boxSpec.range.first)
            boxView.tag = boxSpec.range

            // Inflate category rows
            for (cat in boxSpec.categories) {
                val row = inflater.inflate(R.layout.category_button_row, categoriesContainer, false)
                val label = row.findViewById<TextView>(R.id.category_label)
                val proBadge = row.findViewById<TextView>(R.id.category_pro_badge)
                val icon = row.findViewById<ImageView>(R.id.category_icon)

                label.setText(cat.labelRes)
                proBadge.visibility = if (cat.isPro) View.VISIBLE else View.GONE

                // store category key on row for later lookup and onClick
                row.tag = cat

                // click behaviour:
                row.setOnClickListener {
                    val isPro = checkProPlusStatus()
                    if (cat.isPro && !isPro) {
                        // send to ProActivity
                        startActivity(Intent(this, ProActivity::class.java))
                        return@setOnClickListener
                    }
                    // if the row is disabled, do nothing
                    if (!row.isEnabled) return@setOnClickListener

                    val intent = Intent(this, LearningGamesActivity::class.java)
                    intent.putExtra("difficulty", getSelectedDifficulty())
                    intent.putExtra("category", cat.key)
                    startActivity(intent)
                }

                categoriesContainer.addView(row)
                createdCategoryRows.add(row to cat.key)
            }

            // Setup rewards if this box contains reward levels (10, 15, 20)
            // Show all rewards, but level 10 will be displayed disabled for non-PRO+ users
            val rewardLevelsInBox = REWARD_LEVELS.filter { it in boxSpec.range }
            rewardsButtons.removeAllViews()
            if (rewardLevelsInBox.isNotEmpty()) {
                // show rewards container; each reward is shown as a non-clickable button-like view (styled)
                rewardsContainer.visibility = View.VISIBLE
                // Make label for rewards more explicit: "PRO+ Rewards" is prefixed on each reward label
                for (rl in rewardLevelsInBox) {
                    // Inflate the stylised reward_button layout
                    val rewardView = inflater.inflate(R.layout.reward_button, rewardsButtons, false)
                    rewardView.tag = rl

                    val text = rewardView.findViewById<TextView>(R.id.reward_button_text)
                    val checkIcon = rewardView.findViewById<ImageView>(R.id.reward_check_icon)

                    // Show the label with PRO+ hint — display "PRO+ Rewards" as requested
                    text.text = "PRO+ Rewards: ${getString(R.string.reward_5pct_title)}"
                    val claimed = isRewardClaimed(rl)

                    if (claimed) {
                        checkIcon.setImageResource(R.drawable.ic_check)
                        checkIcon.visibility = View.VISIBLE
                        rewardView.alpha = 1f
                    } else {
                        checkIcon.visibility = View.GONE
                        rewardView.alpha = 0.6f
                    }

                    // rewards are visual only here (auto-applied) so don't allow click
                    rewardView.isClickable = false
                    rewardView.isFocusable = true // accessible

                    // If this is level 10 and user isn't PRO+, present as disabled (visible but inactive)
                    if (rl == 10 && !isProUser) {
                        // visually indicate disabled; actual enabling/claiming is guarded elsewhere
                        rewardView.isEnabled = false
                        rewardView.alpha = 0.35f
                    } else {
                        rewardView.isEnabled = true
                    }

                    rewardsButtons.addView(rewardView)
                }
            } else {
                rewardsContainer.visibility = View.GONE
            }

            // rewardsContainer left hidden by default; other code can populate it via:
            // boxView.findViewById<LinearLayout>(R.id.level_rewards_buttons).addView(...)
            container.addView(boxView)
        }

        // Finally run an initial update pass
        updateCategoryBoxes()
    }

    // Reward persistence helpers (claimed flags remain here)
    private fun isRewardClaimed(level: Int): Boolean {
        val prefs = getSharedPreferences("rewards_prefs", MODE_PRIVATE)
        return prefs.getBoolean("claimed_reward_$level", false)
    }

    private fun setRewardClaimed(level: Int) {
        val prefs = getSharedPreferences("rewards_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("claimed_reward_$level", true).apply()
    }

    /**
     * Apply reward automatically when user reaches the required level.
     * This will persist the +5% multiplier (via XpManager) and mark the reward claimed.
     * This method is idempotent (it checks if reward already claimed).
     */
    private fun applyRewardIfNeeded(level: Int) {
        if (level == 10 && !checkProPlusStatus()) return

        if (isRewardClaimed(level)) return
        // only apply when user has actually reached the level
        val userLevel = XpManager.getLevel(XpManager.getXp(this))
        if (userLevel < level) return

        // persist multiplier in XpManager so future game completions get +5%
        XpManager.addXpBonusMultiplier(this, 0.05f)
        setRewardClaimed(level)
        Toast.makeText(this, "5% XP bonus unlocked at level $level!", Toast.LENGTH_SHORT).show()
        // refresh UI
        updateXpAndLevelStats()
    }

    //For updating when user purchases PRO Version in ProActivity
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
        refreshStreakDisplay()

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

    private fun refreshStreakDisplay() {
        try {
            val toolsLayout = findViewById<LinearLayout>(R.id.tools_layout)
            val streakViewId = resources.getIdentifier("streak_count_text", "id", packageName)
            val streakView = if (streakViewId != 0) findViewById<TextView?>(streakViewId) else null
            if (streakView == null) return
            val streak = StreakManager.getCurrentStreak(this)
            if (streak <= 0) {
                streakView.visibility = View.GONE
            } else {
                streakView.visibility = View.VISIBLE
                streakView.text = streak.toString()
            }
        } catch (_: Exception) {}
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

    private fun updateCategoryBoxes() {
        val xp = XpManager.getXp(this)
        val userLevel = XpManager.getLevel(xp)
        val isProUser = checkProPlusStatus()
        val lives = LivesManager.getLives(this)
        val isEnabled = lives > 0

        // For each box (child of boxes_container)
        val container = findViewById<LinearLayout>(R.id.boxes_container)
        for (i in 0 until container.childCount) {
            val box = container.getChildAt(i)
            val levelRange = box.tag as? IntRange ?: continue
            val title = box.findViewById<TextView>(R.id.level_box_title)
            val categoriesContainer = box.findViewById<LinearLayout>(R.id.level_categories_container)
            val rewardsContainer = box.findViewById<LinearLayout>(R.id.level_rewards_container)
            val rewardsButtons = box.findViewById<LinearLayout>(R.id.level_rewards_buttons)

            val unlocked = userLevel >= levelRange.last
            val current = userLevel in levelRange
            val boxIsUnlocked = unlocked || current

            box.alpha = if (boxIsUnlocked) 1f else 0.5f
            title.setLockDrawable(boxIsUnlocked)

            // set enable/alpha for each category row in this box
            for (j in 0 until categoriesContainer.childCount) {
                val child = categoriesContainer.getChildAt(j)
                val cat = child.tag as? CategorySpec
                if (cat != null) {
                    val childIsEnabled = boxIsUnlocked && isEnabled && (!cat.isPro || isProUser)
                    child.isEnabled = childIsEnabled
                    child.alpha = if (childIsEnabled) 1f else 0.5f
                }
            }

            // update reward views for this box: auto-apply when level reached and show checkmark
            if (rewardsButtons != null && rewardsButtons.childCount > 0) {
                for (k in 0 until rewardsButtons.childCount) {
                    val child = rewardsButtons.getChildAt(k)
                    val rl = child.tag as? Int ?: continue

                    // If user has reached the reward level and it isn't applied yet, apply it
                    if (!isRewardClaimed(rl) && userLevel >= rl) {
                        applyRewardIfNeeded(rl)
                    }

                    val claimed = isRewardClaimed(rl)
                    val checkIcon = child.findViewById<ImageView?>(R.id.reward_check_icon)
                    val textView = child.findViewById<TextView?>(R.id.reward_button_text)
                    // ensure text is set
                    textView?.text = getString(R.string.reward_5pct_title)
                    if (claimed) {
                        checkIcon?.setImageResource(R.drawable.ic_check)
                        checkIcon?.visibility = View.VISIBLE
                        child.alpha = 1f
                    } else {
                        checkIcon?.visibility = View.GONE
                        child.alpha = 0.5f
                    }
                    child.isEnabled = false
                }
                rewardsContainer.visibility = if (rewardsButtons.childCount > 0) View.VISIBLE else View.GONE
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
            // show hours only if > 0
            if (hours > 0) {
                infoText.text = "Out of lives! More lives in $hours hours, $minutes minutes and $seconds seconds."
            } else {
                infoText.text = "Out of lives! More lives in $minutes minutes and $seconds seconds."
            }
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
        // show multiplier (optional, for visibility) by reading stored multiplier
        val multiplier = XpManager.getXpBonusMultiplier(this)
        val multiplierPct = ((multiplier - 1.0f) * 100).toInt()
        findViewById<TextView>(R.id.total_xp_stat).text = "Total XP: $xp"
        findViewById<TextView>(R.id.xp_bonus).text = "+${multiplierPct}% bonus xp"

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
        val isPro = checkProPlusStatus()
        return createdCategoryRows.filter { (view, key) ->
            // consider view enabled and not a pro-category if user isn't pro
            val catSpec = view.tag as? CategorySpec
            if (catSpec == null) false
            else view.isEnabled && (!catSpec.isPro || isPro)
        }.map { it.first to it.second }
    }

    private fun launchRandomUnlockedGame() {
        val unlocked = getAllUnlockedCategoryButtons()
        if (unlocked.isEmpty()) {
            Toast.makeText(this, "No unlocked games available!", Toast.LENGTH_SHORT).show()
            return
        }
        val (btn, category) = unlocked.random()
        val catSpec = btn.tag as? CategorySpec
        val isProCategory = catSpec?.isPro == true
        if (isProCategory && !checkProPlusStatus()) {
            startActivity(Intent(this, ProActivity::class.java))
            return
        }
        val intent = Intent(this, LearningGamesActivity::class.java)
        intent.putExtra("difficulty", getSelectedDifficulty())
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

    // Helper extension to set lock icon on title text
    private fun TextView.setLockDrawable(unlocked: Boolean) {
        val drawable = if (unlocked) R.drawable.ic_lock_open else R.drawable.ic_lock
        setCompoundDrawablesWithIntrinsicBounds(drawable, 0, 0, 0)
    }
}