package com.romaster.launcher.ui

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.romaster.launcher.R

/**
 * Acerca de — mismo layout visual que Romaster LiveWall Engine:
 * fondo about_bg (FILL), título, versión del APK, descripción,
 * iconos de redes oficiales y soporte Buy Me a Coffee.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        // Full-bleed background (CENTER_CROP ≈ FILL)
        root.addView(ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.about_bg)
        })

        // Soft dark scrim so text stays readable over neon photo
        root.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x99000000.toInt())
        })

        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(20), dp(28), dp(36))
        }
        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)

        // Close (top-right)
        content.addView(TextView(this).apply {
            text = "Close"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 15f
            setPadding(dp(8), dp(6), dp(4), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.END }
            setOnClickListener { finish() }
        })

        // Title
        content.addView(TextView(this).apply {
            text = "Romaster Launcher"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 26f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(6))
        })

        // Version from package (build.gradle versionName)
        content.addView(TextView(this).apply {
            text = "Versión ${appVersionName()}"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        })

        // Short pitch
        content.addView(TextView(this).apply {
            text = "Launcher Android con escritorio multipágina, dock, cajón de apps, " +
                "packs de iconos, widgets, Pila de Widgets, Gif's Animados, " +
                "carpetas, transiciones y backup."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setPadding(dp(4), 0, dp(4), dp(14))
        })

        content.addView(TextView(this).apply {
            text = "Developed by Román Ignacio Romero (Romaster).\n" +
                "Thanks for using the app. If you like the project, you\n" +
                "can support it or write to me."
            setTextColor(0xE6FFFFFF.toInt())
            textSize = 14.5f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(22))
        })

        // Contact & social
        content.addView(TextView(this).apply {
            text = "Contact & social"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        })

        val socialRow1 = listOf(
            R.drawable.about_ic_instagram to URL_INSTAGRAM,
            R.drawable.about_ic_threads to URL_THREADS,
            R.drawable.about_ic_youtube to URL_YOUTUBE,
            R.drawable.about_ic_github to URL_GITHUB
        )
        val socialRow2 = listOf(
            R.drawable.about_ic_reddit to URL_REDDIT,
            R.drawable.about_ic_patreon to URL_PATREON,
            R.drawable.about_ic_gmail to URL_MAIL
        )
        content.addView(iconRow(socialRow1))
        content.addView(iconRow(socialRow2).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        })

        content.addView(TextView(this).apply {
            text = "Instagram · Threads · YouTube · GitHub · Reddit · Patreon · Gmail"
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 11.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(18))
        })

        // Divider
        content.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(18)
            }
            setBackgroundColor(0x55FFFFFF)
        })

        // Support
        content.addView(TextView(this).apply {
            text = "Support the project"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        })

        content.addView(ImageButton(this).apply {
            val size = dp(72)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
            setImageResource(R.drawable.about_ic_bmc)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = null
            contentDescription = "Buy Me a Coffee"
            setOnClickListener { openUrl(URL_BMC) }
        })

        content.addView(TextView(this).apply {
            text = "🍺  Buy me a beer"
            setTextColor(0xFF1A1A1A.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xFFFFEB3B.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setOnClickListener { openUrl(URL_BMC) }
        })

        // Features (compact, below support — still complete)
        content.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply {
                topMargin = dp(28)
                bottomMargin = dp(16)
            }
            setBackgroundColor(0x44FFFFFF)
        })

        content.addView(TextView(this).apply {
            text = "Features"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 17f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        })

        val features = listOf(
            "Escritorio multipágina y grid configurable",
            "Dock multipágina con arrastre y tamaños",
            "Cajón de apps: búsqueda, grupos y ocultas",
            "Packs de iconos (Nova / ADW)",
            "Widgets del sistema: colocar, resize y drag",
            "Pila de Widgets con swipe y multi-select",
            "Gif's Animados con transparencia y Aspect Mode",
            "Carpetas, transiciones y modo overview",
            "Backup / restore SAF de layout y ajustes"
        )
        for (f in features) {
            content.addView(TextView(this).apply {
                text = "•  $f"
                setTextColor(0xDDFFFFFF.toInt())
                textSize = 13.5f
                setPadding(dp(8), dp(3), dp(8), dp(3))
            })
        }

        // Footer
        content.addView(TextView(this).apply {
            text = "© 2026 Román Ignacio Romero (Romaster) · Apache 2.0"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(8))
        })
    }

    private fun iconRow(items: List<Pair<Int, String>>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            for ((resId, url) in items) {
                addView(ImageButton(this@AboutActivity).apply {
                    val size = dp(52)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginStart = dp(6)
                        marginEnd = dp(6)
                    }
                    setImageResource(resId)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = null
                    setOnClickListener { openUrl(url) }
                })
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }

    private fun appVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

    companion object {
        const val URL_INSTAGRAM = "https://www.instagram.com/romaster_tuning"
        const val URL_THREADS = "https://www.threads.com/@romaster_tuning"
        const val URL_YOUTUBE = "https://youtube.com/@romaster85"
        const val URL_GITHUB = "https://github.com/Romaster1985"
        const val URL_REDDIT = "https://www.reddit.com/u/Local_Row_8542/s/fnOW0meSeQ"
        const val URL_PATREON = "https://www.patreon.com/romasterdroidtuning"
        const val URL_MAIL = "mailto:roman.ignacio.romero@gmail.com"
        const val URL_BMC = "https://www.buymeacoffee.com/Romaster"
    }
}
