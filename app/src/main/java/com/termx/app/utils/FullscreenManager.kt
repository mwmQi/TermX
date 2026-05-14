package com.termx.app.utils

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.WindowInsetsController

/**
 * Manages fullscreen / immersive mode for the terminal.
 * Hides status bar and navigation bar for maximum terminal space.
 */
object FullscreenManager {

    private var isFullscreen = false

    /**
     * Toggle fullscreen mode.
     */
    fun toggle(activity: Activity): Boolean {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            enterFullscreen(activity)
        } else {
            exitFullscreen(activity)
        }
        return isFullscreen
    }

    /**
     * Enter immersive sticky fullscreen mode.
     * Hides status bar + navigation bar. They reappear on swipe from edge.
     */
    fun enterFullscreen(activity: Activity) {
        isFullscreen = true
        val window = activity.window
        val decorView = window.decorView

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+
            val controller = decorView.windowInsetsController
            controller?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            // Legacy
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }

        // Keep screen on in fullscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Exit fullscreen mode. Restore system bars.
     */
    fun exitFullscreen(activity: Activity) {
        isFullscreen = false
        val window = activity.window
        val decorView = window.decorView

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = decorView.windowInsetsController
            controller?.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
        } else {
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    fun isFullscreen(): Boolean = isFullscreen
}
