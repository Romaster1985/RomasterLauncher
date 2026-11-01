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
    private val appRepo by lazy { (application as RomasterApp).appRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val listView = ListView(this).apply {
            setBackgroundColor(resources.getColor(R.color.romaster_background, theme))
            setPadding(24, 64, 24, 24)
        }
        setContentView(listView)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings)

        val options = listOf(
            "🎨  Pack de iconos",
            "ℹ️  Acerca de Romaster Launcher"
        )

        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, options)

        listView.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> showIconPackPicker()
                1 -> showAbout()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showIconPackPicker() {
        val packs = mutableListOf<IconPackInfo>()
        packs.add(IconPackInfo("__default__", getString(R.string.default_icons), null))
        packs.addAll(iconPackManager.getAvailableIconPacks())

        if (packs.size == 1) {
            Toast.makeText(
                this,
                "No se encontraron packs de iconos instalados.\nInstala uno (Nova, Lawnicons, etc.) e inténtalo de nuevo.",
                Toast.LENGTH_LONG
            ).show()
        }

        val names = packs.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.icon_pack))
            .setItems(names) { _, which ->
                val selected = packs[which]
                if (selected.packageName == "__default__") {
                    iconPackManager.setIconPack(null)
                    Toast.makeText(this, "Iconos por defecto aplicados.\nCierra y vuelve a abrir el cajón.", Toast.LENGTH_LONG).show()
                } else {
                    iconPackManager.setIconPack(selected.packageName)
                    Toast.makeText(this, "Pack aplicado: ${selected.name}\nCierra y vuelve a abrir el cajón.", Toast.LENGTH_LONG).show()
                }
                // Force the app list to rebuild with the new icons
                appRepo.forceRefresh()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("Romaster Launcher")
            .setMessage(
                "Versión 1.0.0\n\n" +
                "Un launcher moderno y ligero.\n\n" +
                "• Mantén pulsado un espacio vacío del escritorio → cambiar fondo / ajustes\n" +
                "• Mantén pulsado un icono del cajón → añadir al escritorio\n" +
                "• Arrastra los iconos del escritorio o del dock para reordenarlos\n" +
                "• Botón de engranaje en el cajón → ajustes y packs de iconos"
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
