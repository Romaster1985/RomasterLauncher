package com.romaster.launcher.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.romaster.launcher.R
import com.romaster.launcher.RomasterApp
import com.romaster.launcher.data.IconPackInfo

class SettingsActivity : AppCompatActivity() {

    private val iconPackManager by lazy { (application as RomasterApp).iconPackManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val listView = ListView(this).apply {
            setBackgroundColor(resources.getColor(R.color.romaster_background, theme))
            setPadding(0, 48, 0, 0)
        }
        setContentView(listView)

        val options = listOf(
            "Pack de iconos",
            "Acerca de Romaster Launcher"
        )

        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, options)

        listView.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> showIconPackPicker()
                1 -> showAbout()
            }
        }
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
                if (selected.packageName == "__default__") {
                    iconPackManager.setIconPack(null)
                    Toast.makeText(this, "Iconos por defecto aplicados", Toast.LENGTH_SHORT).show()
                } else {
                    iconPackManager.setIconPack(selected.packageName)
                    Toast.makeText(this, "Pack aplicado: ${selected.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("Romaster Launcher")
            .setMessage("Versión 1.0.0\n\nUn launcher moderno y ligero creado paso a paso.\nSoporta packs de iconos, fondo de pantalla del sistema, cajón de apps y más.")
            .setPositiveButton("OK", null)
            .show()
    }
}
