package com.romaster.launcher.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.romaster.launcher.RomasterApp
import com.romaster.launcher.data.AppInfo
import com.romaster.launcher.databinding.ActivityAppDrawerBinding
import kotlinx.coroutines.launch

class AppDrawerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppDrawerBinding
    private lateinit var adapter: AppIconAdapter

    private val appRepo by lazy { (application as RomasterApp).appRepository }
    private var allApps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppDrawerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGrid()
        setupSearch()
        loadApps()
    }

    private fun setupGrid() {
        adapter = AppIconAdapter(
            onClick = { app ->
                appRepo.launchApp(app)
                finish()
            },
            onLongClick = { app, _ ->
                addToHome(app)
            }
        )
        binding.appsGrid.layoutManager = GridLayoutManager(this, 4)
        binding.appsGrid.adapter = adapter
    }

    private fun setupSearch() {
        binding.searchApps.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterApps(s?.toString().orEmpty())
            }
        })
    }

    private fun loadApps() {
        lifecycleScope.launch {
            allApps = appRepo.loadApps()
            adapter.submitList(allApps)
        }
    }

    private fun filterApps(query: String) {
        if (query.isBlank()) {
            adapter.submitList(allApps)
        } else {
            val filtered = allApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
            adapter.submitList(filtered)
        }
    }

    private fun addToHome(app: AppInfo) {
        val homeRepo = (application as RomasterApp).homeLayoutRepository
        val current = homeRepo.loadItems().toMutableList()

        if (current.any { it.packageName == app.packageName && it.activityName == app.activityName }) {
            Toast.makeText(this, "Ya está en el escritorio", Toast.LENGTH_SHORT).show()
            return
        }

        current.add(
            com.romaster.launcher.data.HomeItem(
                id = java.util.UUID.randomUUID().toString(),
                packageName = app.packageName,
                activityName = app.activityName,
                label = app.label,
                isDock = false
            )
        )
        homeRepo.saveItems(current)
        Toast.makeText(this, "Añadido al escritorio. Vuelve al home para verlo.", Toast.LENGTH_SHORT).show()
    }
}
