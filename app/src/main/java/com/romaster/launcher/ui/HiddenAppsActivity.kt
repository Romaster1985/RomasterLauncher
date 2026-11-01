package com.romaster.launcher.ui

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.romaster.launcher.RomasterApp
import com.romaster.launcher.data.LauncherPrefs
import kotlinx.coroutines.launch

class HiddenAppsActivity : AppCompatActivity() {

    private val appRepo by lazy { (application as RomasterApp).appRepository }
    private lateinit var prefs: LauncherPrefs
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        title = "Aplicaciones ocultas"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF121212.toInt())
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 48)
        }
        scroll.addView(list)
        setContentView(scroll)

        lifecycleScope.launch {
            val apps = appRepo.loadApps(force = false)
            val hidden = prefs.getHiddenPackages()
            list.removeAllViews()
            for (app in apps) {
                val row = LinearLayout(this@HiddenAppsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(8, 16, 8, 16)
                }
                row.addView(ImageView(this@HiddenAppsActivity).apply {
                    setImageDrawable(app.icon)
                    layoutParams = LinearLayout.LayoutParams(96, 96).apply { marginEnd = 24 }
                })
                row.addView(TextView(this@HiddenAppsActivity).apply {
                    text = app.label
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                val cb = CheckBox(this@HiddenAppsActivity).apply {
                    isChecked = app.packageName in hidden
                    // checked = hidden
                    setOnCheckedChangeListener { _, isChecked ->
                        prefs.setHidden(app.packageName, isChecked)
                    }
                }
                row.addView(cb)
                row.setOnClickListener { cb.isChecked = !cb.isChecked }
                list.addView(row)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
