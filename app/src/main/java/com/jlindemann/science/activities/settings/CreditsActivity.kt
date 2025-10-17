package com.jlindemann.science.activities.settings

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.jlindemann.science.R
import com.jlindemann.science.activities.BaseActivity
import com.jlindemann.science.animations.Anim
import com.jlindemann.science.preferences.ThemePreference
import com.jlindemann.science.utils.TabUtil

class CreditsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themePreference = ThemePreference(this)
        val themePrefValue = themePreference.getValue()

        if (themePrefValue == 100) {
            when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_NO -> { setTheme(R.style.AppTheme) }
                Configuration.UI_MODE_NIGHT_YES -> { setTheme(R.style.AppThemeDark) }
            }
        }
        if (themePrefValue == 0) { setTheme(R.style.AppTheme) }
        if (themePrefValue == 1) { setTheme(R.style.AppThemeDark) }
        setContentView(R.layout.activity_settings_credits) //REMEMBER: Never move any function calls above this

        findViewById<FrameLayout>(R.id.view_cre).systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        //Title Controller
        findViewById<FrameLayout>(R.id.common_title_back_cre_color).visibility = View.INVISIBLE
        findViewById<TextView>(R.id.credits_title).visibility = View.INVISIBLE
        findViewById<FrameLayout>(R.id.common_title_back_cre).elevation = (resources.getDimension(R.dimen.zero_elevation))
        findViewById<ScrollView>(R.id.credits_scroll).getViewTreeObserver()
            .addOnScrollChangedListener(object : ViewTreeObserver.OnScrollChangedListener {
                var y = 300f
                override fun onScrollChanged() {
                    if (findViewById<ScrollView>(R.id.credits_scroll).getScrollY() > 150) {
                        findViewById<FrameLayout>(R.id.common_title_back_cre_color).visibility = View.VISIBLE
                        findViewById<TextView>(R.id.credits_title).visibility = View.VISIBLE
                        findViewById<TextView>(R.id.credits_title_downstate).visibility = View.INVISIBLE
                        findViewById<FrameLayout>(R.id.common_title_back_cre).elevation = (resources.getDimension(R.dimen.one_elevation))
                    } else {
                        findViewById<FrameLayout>(R.id.common_title_back_cre_color).visibility = View.INVISIBLE
                        findViewById<TextView>(R.id.credits_title).visibility = View.INVISIBLE
                        findViewById<TextView>(R.id.credits_title_downstate).visibility = View.VISIBLE
                        findViewById<FrameLayout>(R.id.common_title_back_cre).elevation = (resources.getDimension(R.dimen.zero_elevation))
                    }
                    y = findViewById<ScrollView>(R.id.credits_scroll).getScrollY().toFloat()
                }
            })

        listeners()
        findViewById<ImageButton>(R.id.back_btn_cre).setOnClickListener {
            this.onBackPressed()
        }
    }

    override fun onApplySystemInsets(top: Int, bottom: Int, left: Int, right: Int) {
        val params2 = findViewById<FrameLayout>(R.id.common_title_back_cre).layoutParams as ViewGroup.LayoutParams
        params2.height = top + resources.getDimensionPixelSize(R.dimen.title_bar)
        findViewById<FrameLayout>(R.id.common_title_back_cre).layoutParams = params2

        val params3 = findViewById<TextView>(R.id.credits_title_downstate).layoutParams as ViewGroup.MarginLayoutParams
        params3.topMargin = top + resources.getDimensionPixelSize(R.dimen.title_bar) + resources.getDimensionPixelSize(R.dimen.header_down_margin)
        findViewById<TextView>(R.id.credits_title_downstate).layoutParams = params3
    }


    private fun listeners() {
        findViewById<FrameLayout>(R.id.c_giancarlo).setOnClickListener {
            val packageManager = packageManager
            val blogURL = "https://x.com/ungiancarlo"
            TabUtil.openCustomTab(blogURL, packageManager, this)
        }
        findViewById<FrameLayout>(R.id.c_electro_boy).setOnClickListener {
            val packageManager = packageManager
            val blogURL = "https://github.com/ElectroBoy10"
            TabUtil.openCustomTab(blogURL, packageManager, this)
        }

    }
}



