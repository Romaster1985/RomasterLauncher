package com.romaster.launcher.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.romaster.launcher.RomasterApp
import com.romaster.launcher.data.DrawerGroup
import com.romaster.launcher.data.DrawerGroupStore
import com.romaster.launcher.data.FolderMember

class DrawerGroupsActivity : AppCompatActivity() {

    private lateinit var store: DrawerGroupStore
    private lateinit var list: LinearLayout
    private var groups: MutableList<DrawerGroup> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DrawerGroupStore(this)
        groups = store.load()
        title = "Grupos del cajón"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            setPadding(24, 24, 24, 24)
        }

        val addBtn = Button(this).apply {
            text = "+ Crear grupo"
            setOnClickListener { createGroupDialog() }
        }
        root.addView(addBtn)

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        rebuild()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun rebuild() {
        list.removeAllViews()
        if (groups.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No hay grupos. Creá uno para organizar apps en el cajón."
                setTextColor(0xAAFFFFFF.toInt())
                setPadding(0, 32, 0, 0)
            })
            return
        }
        for (g in groups) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 20, 16, 20)
                setBackgroundColor(0x22FFFFFF)
                setOnClickListener { editMembers(g) }
                setOnLongClickListener {
                    groupMenu(g)
                    true
                }
            }
            row.addView(TextView(this).apply {
                text = g.name
                textSize = 17f
                setTextColor(0xFFFFFFFF.toInt())
            })
            row.addView(TextView(this).apply {
                text = if (g.members.isEmpty()) "Sin aplicaciones"
                else g.members.take(4).joinToString(", ") { it.label } +
                    if (g.members.size > 4) "…" else ""
                textSize = 13f
                setTextColor(0x99FFFFFF.toInt())
            })
            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 })
        }
    }

    private fun createGroupDialog() {
        val input = EditText(this).apply {
            hint = "Nombre del grupo"
            setSingleLine()
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Nuevo grupo")
            .setView(input)
            .setPositiveButton("Crear") { d, _ ->
                val name = input.text.toString().ifBlank { "Grupo" }
                groups.add(store.newGroup(name))
                store.save(groups)
                rebuild()
                d.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun groupMenu(g: DrawerGroup) {
        val items = arrayOf("Editar nombre", "Seleccionar aplicaciones", "Eliminar")
        AlertDialog.Builder(this)
            .setTitle(g.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> renameGroup(g)
                    1 -> editMembers(g)
                    2 -> {
                        groups.removeAll { it.id == g.id }
                        store.save(groups)
                        rebuild()
                    }
                }
            }
            .show()
    }

    private fun renameGroup(g: DrawerGroup) {
        val input = EditText(this).apply {
            setText(g.name)
            setSelection(text.length)
            setSingleLine()
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Editar nombre")
            .setView(input)
            .setPositiveButton("Guardar") { d, _ ->
                g.name = input.text.toString().ifBlank { g.name }
                store.save(groups)
                rebuild()
                d.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun editMembers(g: DrawerGroup) {
        val appRepo = (application as RomasterApp).appRepository
        val cached = appRepo.getCachedApps()
        if (cached.isEmpty()) {
            Toast.makeText(this, "Abrí el cajón del launcher una vez y volvé", Toast.LENGTH_SHORT).show()
            return
        }
        val apps = cached.sortedBy { it.label.lowercase() }
        val selected = g.members.map { it.packageName + "/" + it.activityName }.toMutableSet()
        val checked = BooleanArray(apps.size) { i ->
            selected.contains(apps[i].packageName + "/" + apps[i].activityName)
        }

        val density = resources.displayMetrics.density
        val scroll = android.widget.ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }
        scroll.addView(list)
        val iconSize = (40 * density).toInt()
        val pad = (10 * density).toInt()

        apps.forEachIndexed { i, app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
            }
            val iv = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = pad
                }
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setImageDrawable(
                    try { (app.icon?.constantState?.newDrawable() ?: app.icon)?.mutate() } catch (_: Exception) { app.icon }
                )
            }
            val tv = TextView(this).apply {
                text = app.label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val cb = android.widget.CheckBox(this).apply {
                isChecked = checked[i]
                setOnCheckedChangeListener { _, isChecked -> checked[i] = isChecked }
            }
            row.addView(iv)
            row.addView(tv)
            row.addView(cb)
            row.setOnClickListener { cb.isChecked = !cb.isChecked }
            list.addView(row)
        }

        AlertDialog.Builder(this)
            .setTitle(g.name)
            .setView(scroll)
            .setPositiveButton("Guardar") { d, _ ->
                g.members.clear()
                for (i in apps.indices) {
                    if (checked[i]) {
                        val a = apps[i]
                        g.members.add(FolderMember(a.packageName, a.activityName, a.label))
                    }
                }
                store.save(groups)
                rebuild()
                d.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
