package com.romaster.launcher.data

import android.graphics.drawable.Drawable

/**
 * Represents an installed application that can be launched.
 */
data class AppInfo(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable?,
    val isSystemApp: Boolean = false
) {
    val uniqueId: String
        get() = "$packageName/$activityName"
}

/**
 * Member of a desktop folder.
 */
data class FolderMember(
    val packageName: String,
    val activityName: String,
    val label: String
) {
    val uniqueId: String get() = "$packageName/$activityName"
}

/**
 * Represents an icon placed on the home screen (desktop, dock or folder).
 */
data class HomeItem(
    val id: String,
    val packageName: String = "",
    val activityName: String = "",
    val label: String = "",
    var screen: Int = 0,
    var cellX: Int = 0,
    var cellY: Int = 0,
    var spanX: Int = 1,
    var spanY: Int = 1,
    var isDock: Boolean = false,
    var isFolder: Boolean = false,
    var folderName: String = "Carpeta",
    var folderMembers: MutableList<FolderMember> = mutableListOf(),
    /** True when this item is an App Widget hosted on the desktop */
    var isWidget: Boolean = false,
    /** AppWidgetHost id allocated for this widget (-1 if none) */
    var appWidgetId: Int = -1
)

/**
 * Simple representation of an available icon pack.
 */
data class IconPackInfo(
    val packageName: String,
    val name: String,
    val icon: Drawable?
)
