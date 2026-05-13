package com.termx.app.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages app preferences for terminal settings.
 */
class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("termx_prefs", Context.MODE_PRIVATE)

    // Font size in SP
    var fontSize: Float
        get() = prefs.getFloat("font_size", 14f)
        set(value) = prefs.edit().putFloat("font_size", value).apply()

    // Font type name
    var fontType: String
        get() = prefs.getString("font_type", "Monospace (Default)") ?: "Monospace (Default)"
        set(value) = prefs.edit().putString("font_type", value).apply()

    // Theme name
    var themeName: String
        get() = prefs.getString("theme_name", "Catppuccin Mocha") ?: "Catppuccin Mocha"
        set(value) = prefs.edit().putString("theme_name", value).apply()

    // Shell path
    var shellPath: String
        get() = prefs.getString("shell_path", "/system/bin/sh") ?: "/system/bin/sh"
        set(value) = prefs.edit().putString("shell_path", value).apply()

    // Scrollback buffer size
    var scrollbackSize: Int
        get() = prefs.getInt("scrollback_size", 5000)
        set(value) = prefs.edit().putInt("scrollback_size", value).apply()

    // Show extra keys bar
    var showExtraKeys: Boolean
        get() = prefs.getBoolean("show_extra_keys", true)
        set(value) = prefs.edit().putBoolean("show_extra_keys", value).apply()

    // Cursor blink
    var cursorBlink: Boolean
        get() = prefs.getBoolean("cursor_blink", true)
        set(value) = prefs.edit().putBoolean("cursor_blink", value).apply()

    // Cursor style: block, underline, bar
    var cursorStyle: String
        get() = prefs.getString("cursor_style", "block") ?: "block"
        set(value) = prefs.edit().putString("cursor_style", value).apply()

    // Bell mode: vibrate, visible, ignore
    var bellMode: String
        get() = prefs.getString("bell_mode", "vibrate") ?: "vibrate"
        set(value) = prefs.edit().putString("bell_mode", value).apply()

    // Keep screen on
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", false)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()

    // Close all sessions on exit
    var closeOnExit: Boolean
        get() = prefs.getBoolean("close_on_exit", true)
        set(value) = prefs.edit().putBoolean("close_on_exit", value).apply()

    // Fullscreen mode
    var fullscreen: Boolean
        get() = prefs.getBoolean("fullscreen", false)
        set(value) = prefs.edit().putBoolean("fullscreen", value).apply()

    // Night mode (auto theme based on system)
    var nightMode: Boolean
        get() = prefs.getBoolean("night_mode", false)
        set(value) = prefs.edit().putBoolean("night_mode", value).apply()

    // Show keyboard toggle button
    var showKeyboardToggle: Boolean
        get() = prefs.getBoolean("show_keyboard_toggle", true)
        set(value) = prefs.edit().putBoolean("show_keyboard_toggle", value).apply()

    // Back key sends ESC
    var backKeyEscape: Boolean
        get() = prefs.getBoolean("back_key_escape", false)
        set(value) = prefs.edit().putBoolean("back_key_escape", value).apply()

    // Session swipe enabled
    var sessionSwipe: Boolean
        get() = prefs.getBoolean("session_swipe", true)
        set(value) = prefs.edit().putBoolean("session_swipe", value).apply()

    // Terminal margin horizontal (dp)
    var terminalMarginH: Int
        get() = prefs.getInt("terminal_margin_h", 3)
        set(value) = prefs.edit().putInt("terminal_margin_h", value).apply()

    // Terminal margin vertical (dp)
    var terminalMarginV: Int
        get() = prefs.getInt("terminal_margin_v", 0)
        set(value) = prefs.edit().putInt("terminal_margin_v", value).apply()

    // Allow external apps
    var allowExternalApps: Boolean
        get() = prefs.getBoolean("allow_external_apps", false)
        set(value) = prefs.edit().putBoolean("allow_external_apps", value).apply()

    // Use native PTY (if available)
    var useNativePty: Boolean
        get() = prefs.getBoolean("use_native_pty", true)
        set(value) = prefs.edit().putBoolean("use_native_pty", value).apply()

    // Package repository URL
    var repoUrl: String
        get() = prefs.getString("repo_url", "https://repo.termx.app") ?: "https://repo.termx.app"
        set(value) = prefs.edit().putString("repo_url", value).apply()

    // Auto-update package index on startup
    var autoUpdateIndex: Boolean
        get() = prefs.getBoolean("auto_update_index", false)
        set(value) = prefs.edit().putBoolean("auto_update_index", value).apply()

    // Initial working directory — defaults to $PREFIX/home for package manager support
    var initialDir: String
        get() = prefs.getString("initial_dir", "/data/data/com.termx.app/files/home") ?: "/data/data/com.termx.app/files/home"
        set(value) = prefs.edit().putString("initial_dir", value).apply()

    companion object {
        @Volatile
        private var instance: PreferenceManager? = null

        fun getInstance(context: Context): PreferenceManager {
            return instance ?: synchronized(this) {
                instance ?: PreferenceManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
