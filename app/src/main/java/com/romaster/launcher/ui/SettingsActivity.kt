package com.romaster.launcher.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.romaster.launcher.R
import com.romaster.launcher.RomasterApp
import com.romaster.launcher.data.BackupManager
import com.romaster.launcher.data.IconPackInfo
import com.romaster.launcher.data.LauncherPrefs
import com.romaster.launcher.data.DrawerGroupStore
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private val iconPackManager by lazy { (application as RomasterApp).iconPackManager }
    private val appRepo by lazy { (application as RomasterApp).appRepository }
    private val homeRepo by lazy { (application as RomasterApp).homeLayoutRepository }
    private lateinit var prefs: LauncherPrefs
    private lateinit var rootList: LinearLayout
    private lateinit var backupManager: BackupManager
    /** Drawer-only prefs changed (alpha, hidden, groups, drawer icon size) */
    private var dirtyDrawer = false
    /** Desktop/dock/grid/layout prefs or backup restore */
    private var dirtyLayout = false

    private val transitions = listOf(
        "none" to "Ninguno",
        "simple" to "Simple",
        "cube" to "Cubo",
        "flip" to "Dar la vuelta",
        "flip_vertical" to "Dar la vuelta en vertical",
        "accordion" to "Acordeón",
        "zoom_fade" to "Ampliar y desvanecer",
        "crystal" to "Cristal"
    )

    companion object {
        private const val REQ_EXPORT = 1001
        private const val REQ_IMPORT = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        backupManager = BackupManager(this, prefs, homeRepo, iconPackManager)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF673AB7.toInt())
            isFillViewport = true
        }
        rootList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(40))
        }
        scroll.addView(rootList)
        setContentView(scroll)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        // Refresh "X ocultas" counter after returning from HiddenAppsActivity
        if (::rootList.isInitialized && rootList.childCount > 0) {
            rebuild()
        }
    }

    private fun markExitFlags() {
        if (dirtyLayout) prefs.pendingLayoutReload = true
        if (dirtyDrawer) prefs.pendingDrawerReload = true
    }

    override fun onSupportNavigateUp(): Boolean {
        markExitFlags()
        finish()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        markExitFlags()
        super.onBackPressed()
    }

    private fun rebuild() {
        rootList.removeAllViews()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Tight to status/action bar and to first section (match mockup)
            setPadding(0, 0, 0, dp(4))
        }
        // Full logo artwork (name is already in the PNG) — large, minimal margins
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.romaster_launcher_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            // ~full content width; height follows PNG aspect (no forced square crop)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                // Soft max so it doesn't dominate on tall tablets
                // (ImageView still scales down via FIT_CENTER + parent width)
            }
            // Prefer a large visual size on phones (~280–320 dp wide area)
            minimumHeight = dp(220)
            maxHeight = dp(320)
        })
        header.addView(TextView(this).apply {
            text = "Versión ${appVersionName()}"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        })
        rootList.addView(header)

        section("Escritorio")
        row("Cuadrícula", "${prefs.desktopColumns}×${prefs.desktopRows}") { showGridPicker() }
        row("Tamaño de iconos del escritorio", "${prefs.desktopIconSizePercent}%") {
            showPercentPicker("Tamaño iconos escritorio", prefs.desktopIconSizePercent) {
                prefs.desktopIconSizePercent = it; dirtyLayout = true; rebuild()
            }
        }
        row("Separación escritorio–dock", "${prefs.desktopDockGapDp} dp") {
            showDpPicker("Separación escritorio y dock", prefs.desktopDockGapDp, 0, 48) {
                prefs.desktopDockGapDp = it; dirtyLayout = true; rebuild()
            }
        }
        val wpScrollLabels = mapOf("on" to "Activado", "off" to "Desactivado", "inverse" to "Inverso")
        row("Desplazamiento del fondo", wpScrollLabels[prefs.wallpaperScroll] ?: "Activado") {
            val opts = arrayOf("Activado", "Desactivado", "Inverso")
            val keys = arrayOf("on", "off", "inverse")
            val cur = keys.indexOf(prefs.wallpaperScroll).coerceAtLeast(0)
            android.app.AlertDialog.Builder(this)
                .setTitle("Desplazamiento del fondo")
                .setSingleChoiceItems(opts, cur) { d, which ->
                    prefs.wallpaperScroll = keys[which]; dirtyLayout = true
                    d.dismiss()
                    rebuild()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        row("Efecto de desplazamiento", transitions.firstOrNull { it.first == prefs.pageTransition }?.second ?: "Simple") {
            showTransitionPicker()
        }
        checkRow("Animaciones de desbloqueo", prefs.unlockAnimationsEnabled) {
            prefs.unlockAnimationsEnabled = it
            rebuild() // refresh checkbox UI immediately (same as other switches)
        }
        checkRow("Botón del cajón de apps", prefs.showDrawerButton) {
            prefs.showDrawerButton = it; dirtyLayout = true; rebuild()
        }
        checkRow("Mostrar dock", prefs.showDock) {
            prefs.showDock = it; dirtyLayout = true; rebuild()
        }
        if (prefs.showDock) {
            row("Tamaño de iconos del dock", "${prefs.dockIconSizePercent}%") {
                showPercentPicker("Tamaño iconos dock", prefs.dockIconSizePercent) {
                    prefs.dockIconSizePercent = it; dirtyLayout = true; rebuild()
                }
            }
            row("Separación entre iconos del dock", "${prefs.dockIconGapDp} dp") {
                showDpPicker("Separación iconos dock", prefs.dockIconGapDp, 0, 32) {
                    prefs.dockIconGapDp = it; dirtyLayout = true; rebuild()
                }
            }
            row("Posición del dock", if (prefs.dockAboveDrawerButton) "Encima del botón del cajón" else "Debajo del botón del cajón") {
                prefs.dockAboveDrawerButton = !prefs.dockAboveDrawerButton; dirtyLayout = true
                rebuild()
            }
            checkRow("Borde cristal del dock", prefs.dockGlassBorder) {
                prefs.dockGlassBorder = it; dirtyLayout = true; rebuild()
            }
        }

        section("Carpetas")
        row(
            "Modo de carpetas",
            if (prefs.folderScrollMode == "pages") "Por páginas (como el dock)" else "Scroll vertical"
        ) {
            prefs.folderScrollMode = if (prefs.folderScrollMode == "pages") "vertical" else "pages"
            dirtyLayout = true
            rebuild()
        }
        row("Cuadrícula de carpetas", "${prefs.folderColumns}×${prefs.folderRows}") {
            showFolderGridPicker()
        }

        section("Cajón de aplicaciones")
        row("Tamaño de iconos del cajón", "${prefs.drawerIconSizePercent}%") {
            showPercentPicker("Tamaño iconos cajón", prefs.drawerIconSizePercent) {
                prefs.drawerIconSizePercent = it; dirtyDrawer = true; rebuild()
            }
        }
        checkRow("Agrupar en orden alfabético", prefs.drawerAlphabetical) {
            prefs.drawerAlphabetical = it; dirtyDrawer = true; rebuild()
        }

        row("Grupos del cajón", "Carpetas y pestañas personalizadas del cajón") {
            startActivity(android.content.Intent(this, DrawerGroupsActivity::class.java))
        }
        row("Aplicaciones ocultas", "${prefs.getHiddenPackages().size} ocultas") {
            dirtyDrawer = true; startActivity(Intent(this, HiddenAppsActivity::class.java))
        }

        section("Apariencia")
        val pack = iconPackManager.getCurrentPackPackage()
        row("Pack de iconos", if (pack.isNullOrBlank()) "Por defecto" else pack) { showIconPackPicker() }

        section("Sistema")
        row("Launcher predeterminado", "Abrir configuración del sistema") { openDefaultLauncherSettings() }
        row("Copia de seguridad", "Elegir carpeta y nombre…") { exportBackup() }
        row("Restaurar copia", "Elegir JSON o ZIP…") { importBackup() }
        row("Acerca de", "Romaster Launcher") { showAbout() }
    }

    private fun section(title: String) {
        rootList.addView(TextView(this).apply {
            text = title.uppercase()
            setTextColor(0xFF64B5F6.toInt())
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(12), 0, dp(6))
            letterSpacing = 0.08f
        })
    }

    private fun row(title: String, subtitle: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBg(0xFF1B2838.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = title; setTextColor(0xFFFFFFFF.toInt()); textSize = 16f
        })
        row.addView(TextView(this).apply {
            text = subtitle; setTextColor(0xFF90A4AE.toInt()); textSize = 13f
            setPadding(0, dp(2), 0, 0)
        })
        rootList.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
    }

    private fun checkRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBg(0xFF1B2838.toInt())
            isClickable = true
            isFocusable = true
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title; setTextColor(0xFFFFFFFF.toInt()); textSize = 16f
        })
        texts.addView(TextView(this).apply {
            text = if (checked) "Activado" else "Desactivado"
            setTextColor(0xFF90A4AE.toInt()); textSize = 13f
        })
        row.addView(texts)
        row.addView(TextView(this).apply {
            text = if (checked) "☑" else "☐"
            textSize = 22f
            setTextColor(0xFF64B5F6.toInt())
        })
        row.setOnClickListener { onChange(!checked) }
        rootList.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
    }

    private fun roundedBg(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(color)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun showDpPicker(title: String, current: Int, minDp: Int, maxDp: Int, onPick: (Int) -> Unit) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val valueLabel = TextView(this).apply {
            text = "$current dp"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, pad / 2)
        }
        val seek = android.widget.SeekBar(this).apply {
            this.max = (maxDp - minDp).coerceAtLeast(0)
            progress = (current - minDp).coerceIn(0, maxDp - minDp)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    valueLabel.text = "${progress + minDp} dp"
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        container.addView(valueLabel)
        container.addView(seek)
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("OK") { _, _ -> onPick(seek.progress + minDp) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showPercentPicker(title: String, current: Int, onSelected: (Int) -> Unit) {
        val values = (80..150 step 5).map { "$it%" }.toTypedArray()
        val idx = ((current - 80) / 5).coerceIn(0, values.size - 1)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(values, idx) { d, which ->
                onSelected(80 + which * 5); d.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showFolderGridPicker() {
        val options = arrayOf("Columnas", "Filas")
        AlertDialog.Builder(this)
            .setTitle("Cuadrícula de carpetas")
            .setItems(options) { _, which ->
                val title = if (which == 0) "Columnas" else "Filas"
                val min = 2
                val max = 6
                val cur = if (which == 0) prefs.folderColumns else prefs.folderRows
                val values = (min..max).map { it.toString() }.toTypedArray()
                val idx = (cur - min).coerceIn(0, values.size - 1)
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setSingleChoiceItems(values, idx) { d, w ->
                        val v = min + w
                        if (which == 0) prefs.folderColumns = v else prefs.folderRows = v
                        dirtyLayout = true
                        d.dismiss()
                        rebuild()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .show()
    }

    private fun showGridPicker() {
        AlertDialog.Builder(this)
            .setTitle("Cuadrícula")
            .setItems(arrayOf("Columnas: ${prefs.desktopColumns}", "Filas: ${prefs.desktopRows}")) { _, which ->
                val title = if (which == 0) "Columnas" else "Filas"
                val min = if (which == 0) 3 else 4
                val max = if (which == 0) 8 else 10
                val cur = if (which == 0) prefs.desktopColumns else prefs.desktopRows
                val values = (min..max).map { it.toString() }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setSingleChoiceItems(values, cur - min) { d, w ->
                        if (which == 0) prefs.desktopColumns = min + w
                        else prefs.desktopRows = min + w
                        dirtyLayout = true
                        rebuild(); d.dismiss()
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showTransitionPicker() {
        val labels = transitions.map { it.second }.toTypedArray()
        val current = transitions.indexOfFirst { it.first == prefs.pageTransition }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Efecto de desplazamiento")
            .setSingleChoiceItems(labels, current) { d, which ->
                prefs.pageTransition = transitions[which].first
                dirtyLayout = true
                rebuild(); d.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showIconPackPicker() {
        val packs = mutableListOf<IconPackInfo>()
        packs.add(IconPackInfo("__default__", getString(R.string.default_icons), null))
        packs.addAll(iconPackManager.getAvailableIconPacks())
        val names = packs.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.icon_pack))
            .setItems(names) { _, which ->
                val selected = packs[which]
                if (selected.packageName == "__default__") iconPackManager.setIconPack(null)
                else iconPackManager.setIconPack(selected.packageName)
                appRepo.forceRefresh()
                lifecycleScope.launch {
                    appRepo.loadApps(force = true)
                    // HomeActivity keeps a local allApps cache — force full desktop+drawer
                    // rebuild when the user returns so icons actually change on screen.
                    prefs.pendingLayoutReload = true
                    prefs.pendingDrawerReload = true
                    Toast.makeText(this@SettingsActivity, "Pack aplicado", Toast.LENGTH_SHORT).show()
                    rebuild()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun openDefaultLauncherSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo abrir", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportBackup() {
        val withMedia = backupManager.hasMediaFiles()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            if (withMedia) {
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "romaster_backup.zip")
            } else {
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "romaster_backup.json")
            }
        }
        startActivityForResult(intent, REQ_EXPORT)
    }

    private fun importBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "application/zip", "application/octet-stream", "*/*"))
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_EXPORT -> {
                try {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        val asZip = backupManager.writeBackup(os)
                        Toast.makeText(
                            this,
                            if (asZip) "Copia ZIP guardada (incluye imágenes/gifs)"
                            else "Copia JSON guardada",
                            Toast.LENGTH_SHORT
                        ).show()
                    } ?: throw Exception("No se pudo escribir")
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            REQ_IMPORT -> {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        backupManager.restoreFromStream(input)
                    } ?: throw Exception("Archivo vacío")
                    // pendingHomeReload already set inside restore
                    prefs.pendingLayoutReload = true
                    prefs.pendingDrawerReload = true
                    dirtyLayout = true
                    dirtyDrawer = true
                    appRepo.forceRefresh()
                    lifecycleScope.launch {
                        appRepo.loadApps(force = true)
                        Toast.makeText(
                            this@SettingsActivity,
                            "Restaurado. Volvé al escritorio para aplicar.",
                            Toast.LENGTH_LONG
                        ).show()
                        rebuild()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun appVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }

    private fun showAbout() {
        startActivity(Intent(this, AboutActivity::class.java))
    }
}

