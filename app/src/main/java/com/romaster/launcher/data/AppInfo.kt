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
 * One member of a widget stack (provider component + host id).
 */
data class StackWidgetMember(
    val packageName: String,
    val className: String,
    var appWidgetId: Int = -1
) {
    val providerKey: String get() = "$packageName/$className"
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
    var appWidgetId: Int = -1,
    /** True when this item is a Widget Stack (multiple widgets in one cell) */
    var isWidgetStack: Boolean = false,
    /** Members of the stack (order = page order). Empty stack shows the + placeholder. */
    var stackMembers: MutableList<StackWidgetMember> = mutableListOf(),
    /** true = vertical swipe (default, Action-style); false = horizontal */
    var stackSwipeVertical: Boolean = true,
    /** Current visible page index inside the stack */
    var stackCurrentPage: Int = 0,
    /** True when this is the custom animated-GIF widget */
    var isGifWidget: Boolean = false,
    /** Absolute path to the GIF file under app filesDir (empty until user picks one) */
    var gifPath: String = "",
    /**
     * How the GIF is shown inside its cell:
     * "fill" | "fit" | "stretch" | "crop" (crop = original pixels, centered, clipped)
     */
    var gifAspectMode: String = "fit",
    /** Relative paint order among widgets on the same page (higher = in front) */
    var zOrder: Int = 0,
    /**
     * Placement mode for widgets:
     * false = snap to desktop grid (cellX/cellY + spanX/spanY)
     * true  = free pixel position (freeX/freeY) and optional freeW/freeH size
     */
    var freePlacement: Boolean = false,
    /** Pixel X/Y of top-left inside desktopContainer when freePlacement */
    var freeX: Float = 0f,
    var freeY: Float = 0f,
    /** Pixel width/height when freePlacement (0 = derive from span) */
    var freeW: Int = 0,
    var freeH: Int = 0,
    /** JSON list of ReactionRecipe for IMG/GIF reactions (empty = none). */
    var reactionsJson: String = "",
    /**
     * Last visual transform from reaction animations.
     * Restored when the widget view is recreated (page change, etc.).
     */
    var animTx: Float = 0f,
    var animTy: Float = 0f,
    var animRot: Float = 0f,
    var animScale: Float = 1f,
    var animAlpha: Float = 1f,
    /**
     * true  = widget drawn above desktop icons (default)
     * false = widget drawn behind desktop icons
     */
    var aboveIcons: Boolean = true
) {
    /** Convenience: this item occupies widget space (single widget, stack, or GIF). */
    val isAnyWidget: Boolean get() = isWidget || isWidgetStack || isGifWidget
}

/**
 * Simple representation of an available icon pack.
 */
data class IconPackInfo(
    val packageName: String,
    val name: String,
    val icon: Drawable?
)
