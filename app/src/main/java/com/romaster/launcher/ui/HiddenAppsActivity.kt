package com.romaster.launcher.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.romaster.launcher.data.AppRepository
import com.romaster.launcher.data.LauncherPrefs
import kotlinx.coroutines.launch

/**
 * List of installed apps with checkboxes to hide them from the drawer.
 * Changes are applied only when the user taps Guardar (Apply/Cancel pattern).
 */
class HiddenAppsActivity : AppCompatActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var list: LinearLayout
    private val pendingHidden = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        pendingHidden.clear()
        pendingHidden.addAll(prefs.getHiddenPackages())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1B2A.toInt())
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        scroll.addView(list)
        root.addView(scroll)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 16, 24, 24)
            setBackgroundColor(0xFF152238.toInt())
            gravity = Gravity.END
        }
        val btnCancel = Button(this).apply {
            text = "Cancelar"
            setOnClickListener { finish() }
        }
        val btnApply = Button(this).apply {
            text = "Guardar"
            setOnClickListener {
                prefs.setHiddenPackages(pendingHidden.toSet())
                prefs.pendingDrawerReload = true
                finish()
            }
        }
        bar.addView(btnCancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 12
        })
        bar.addView(btnApply, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        setContentView(root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Aplicaciones ocultas"

        lifecycleScope.launch {
            val apps = AppRepository(this@HiddenAppsActivity).loadApps(force = false)
                .sortedBy { it.label.lowercase() }
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
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val cb = CheckBox(this@HiddenAppsActivity).apply {
                    isChecked = app.packageName in pendingHidden
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) pendingHidden.add(app.packageName)
                        else pendingHidden.remove(app.packageName)
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
