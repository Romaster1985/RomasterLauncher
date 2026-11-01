package com.romaster.launcher.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class WallpaperPickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(Intent(Intent.ACTION_SET_WALLPAPER))
        } catch (e: Exception) {
            // ignore
        }
        finish()
    }
}
