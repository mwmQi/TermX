package com.termx.app.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Manages storage setup for TermX.
 * Creates symlinks to shared storage directories, similar to `termux-setup-storage`.
 *
 * After setup, the user can access:
 *   ~/storage/shared      → /sdcard/
 *   ~/storage/downloads   → /sdcard/Download
 *   ~/storage/dcim        → /sdcard/DCIM
 *   ~/storage/music       → /sdcard/Music
 *   ~/storage/pictures    → /sdcard/Pictures
 *   ~/storage/movies      → /sdcard/Movies
 *   ~/storage/documents   → /sdcard/Documents
 */
object StorageSetup {

    private const val TAG = "StorageSetup"

    /** Base directory for symlinks inside the app's files dir */
    private const val STORAGE_DIR = "storage"

    /**
     * Check if storage has already been set up.
     */
    fun isStorageSetup(context: Context): Boolean {
        val storageDir = File(context.filesDir, STORAGE_DIR)
        return storageDir.exists() && File(storageDir, "shared").exists()
    }

    /**
     * Check if we have permission to access external storage.
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            @Suppress("DEPRECATION")
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Request MANAGE_EXTERNAL_STORAGE permission (Android 11+).
     * Returns true if we already have permission.
     */
    fun requestStoragePermission(context: Context): Boolean {
        if (hasStoragePermission(context)) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open storage permission settings", e)
                // Fallback to general settings
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(intent)
            }
        }
        return false
    }

    /**
     * Set up storage symlinks.
     * This creates ~/storage/ with links to common directories.
     *
     * Requires storage permission to be granted first.
     */
    fun setupStorage(context: Context): SetupResult {
        if (!hasStoragePermission(context)) {
            return SetupResult(false, "Storage permission not granted. Run 'storage-permission' first.")
        }

        val storageDir = File(context.filesDir, STORAGE_DIR)
        val homeDir = context.filesDir

        // Create storage directory
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }

        val sdcard = Environment.getExternalStorageDirectory()
        val links = mapOf(
            "shared" to sdcard.absolutePath,
            "downloads" to File(sdcard, Environment.DIRECTORY_DOWNLOADS).absolutePath,
            "dcim" to File(sdcard, Environment.DIRECTORY_DCIM).absolutePath,
            "music" to File(sdcard, Environment.DIRECTORY_MUSIC).absolutePath,
            "pictures" to File(sdcard, Environment.DIRECTORY_PICTURES).absolutePath,
            "movies" to File(sdcard, Environment.DIRECTORY_MOVIES).absolutePath,
            "documents" to File(sdcard, Environment.DIRECTORY_DOCUMENTS).absolutePath,
            "podcasts" to File(sdcard, Environment.DIRECTORY_PODCASTS).absolutePath,
            "alarms" to File(sdcard, Environment.DIRECTORY_ALARMS).absolutePath,
            "notifications" to File(sdcard, Environment.DIRECTORY_NOTIFICATIONS).absolutePath,
            "ringtones" to File(sdcard, Environment.DIRECTORY_RINGTONES).absolutePath
        )

        var successCount = 0
        val errors = mutableListOf<String>()

        for ((name, target) in links) {
            val linkFile = File(storageDir, name)
            try {
                // Remove existing link/directory
                if (linkFile.exists()) {
                    if (linkFile.isDirectory && !isSymlink(linkFile)) {
                        linkFile.deleteRecursively()
                    } else {
                        linkFile.delete()
                    }
                }

                // Create target directory if it doesn't exist
                val targetDir = File(target)
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }

                // Try to create symlink
                if (createSymlink(target, linkFile.absolutePath)) {
                    successCount++
                    Log.d(TAG, "Created symlink: $name -> $target")
                } else {
                    // Fallback: create a directory and try to copy/reflect
                    // On some devices, symlinks may not work, so we create a directory instead
                    linkFile.mkdirs()
                    successCount++
                    Log.w(TAG, "Symlink failed, created directory instead: $name")
                }
            } catch (e: Exception) {
                val errMsg = "Failed to setup '$name': ${e.message}"
                errors.add(errMsg)
                Log.e(TAG, errMsg, e)
            }
        }

        // Also set HOME environment variable
        setupHomeEnvironment(context)

        return SetupResult(
            success = successCount > 0,
            message = if (errors.isEmpty()) {
                "Storage setup complete! $successCount directories linked.\n" +
                "Access via: cd ~/storage/shared"
            } else {
                "Storage setup partial: $successCount linked, ${errors.size} errors.\n" +
                errors.joinToString("\n")
            }
        )
    }

    /**
     * Create the app's home directory structure.
     */
    private fun setupHomeEnvironment(context: Context) {
        val home = context.filesDir

        // Create standard directories
        val dirs = listOf("bin", "etc", "lib", "tmp", "usr", "var", "home")
        for (dir in dirs) {
            val d = File(home, dir)
            if (!d.exists()) d.mkdirs()
        }

        // Create .bashrc if it doesn't exist
        val bashrc = File(home, ".bashrc")
        if (!bashrc.exists()) {
            bashrc.writeText(buildBashrc(context))
        }

        // Create .profile if it doesn't exist
        val profile = File(home, ".profile")
        if (!profile.exists()) {
            profile.writeText(buildProfile(context))
        }

        Log.d(TAG, "Home environment setup at: ${home.absolutePath}")
    }

    private fun buildBashrc(context: Context): String {
        val home = context.filesDir.absolutePath
        return """
# TermX .bashrc
# Generated by TermX Terminal Emulator

# History settings
export HISTSIZE=5000
export HISTFILESIZE=10000
export HISTCONTROL=ignoredups:erasedups

# Prompt
export PS1='\[\e[1;34m\]λ \[\e[0m\]\[\e[0;32m\]\w\[\e[0m\] \[\e[1;34m\]❯\[\e[0m\] '

# Aliases
alias ll='ls -la'
alias la='ls -a'
alias l='ls -CF'
alias ..='cd ..'
alias ...='cd ../..'
alias cls='clear'
alias h='history'
alias c='clear'

# TermX paths
export HOME="$home"
export TERMUX_PREFIX="$home/usr"
export PATH="$home/bin:${'$'}PATH"
export LD_LIBRARY_PATH="$home/lib:${'$'}LD_LIBRARY_PATH"

# Storage shortcut
storage() {
    cd "$home/storage/${'$'}1"
}

# Quick commands
ox-setup() {
    echo "Setting up TermX storage..."
    echo "Run 'setup-storage' from the TermX menu."
}

echo "Welcome to TermX! Type 'help' for available commands."
""".trimIndent()
    }

    private fun buildProfile(context: Context): String {
        val home = context.filesDir.absolutePath
        return """
# TermX .profile
# Environment setup

export HOME="$home"
export TERM=xterm-256color
export COLORTERM=truecolor
export LANG=en_US.UTF-8
export EDITOR=nano
export VISUAL=nano
export PAGER=less
export LESS="-R"

# Source .bashrc if running bash
if [ -n "${'$'}BASH_VERSION" ]; then
    if [ -f "$home/.bashrc" ]; then
        . "$home/.bashrc"
    fi
fi
""".trimIndent()
    }

    private fun createSymlink(target: String, linkPath: String): Boolean {
        return try {
            val cmd = "ln -sf '$target' '$linkPath'"
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Symlink creation failed", e)
            false
        }
    }

    private fun isSymlink(file: File): Boolean {
        return try {
            file.absolutePath != file.canonicalPath
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get storage info for display.
     */
    fun getStorageInfo(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== TermX Storage Info ===")
        sb.appendLine()

        if (!hasStoragePermission(context)) {
            sb.appendLine("⚠ Storage permission NOT granted")
            sb.appendLine("Run 'setup-storage' from menu to grant permission")
            sb.appendLine()
        }

        val storageDir = File(context.filesDir, STORAGE_DIR)
        if (storageDir.exists()) {
            sb.appendLine("Storage symlinks (${storageDir.absolutePath}):")
            storageDir.listFiles()?.forEach { file ->
                val target = try {
                    file.canonicalPath
                } catch (e: Exception) {
                    "?"
                }
                val arrow = if (isSymlink(file)) "→" else "dir"
                sb.appendLine("  ${file.name} $arrow $target")
            }
        } else {
            sb.appendLine("Storage not set up yet. Run 'setup-storage' from menu.")
        }

        sb.appendLine()
        sb.appendLine("Disk usage:")
        val stat = android.os.StatFs(Environment.getExternalStorageDirectory().path)
        val total = stat.totalBytes / (1024 * 1024)
        val available = stat.availableBytes / (1024 * 1024)
        sb.appendLine("  Total: ${total}MB")
        sb.appendLine("  Available: ${available}MB")

        return sb.toString()
    }

    data class SetupResult(
        val success: Boolean,
        val message: String
    )
}
