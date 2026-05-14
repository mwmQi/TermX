package com.termx.app.utils

import android.content.Context
import android.util.Log
import java.io.*

/**
 * Installs shell command wrappers from assets/bin/ to the app's bin directory.
 * Makes them executable so they can be called from the terminal.
 */
object AssetInstaller {

    private const val TAG = "AssetInstaller"
    private const val ASSETS_BIN_DIR = "bin"
    private const val VERSION_FILE = ".assets_version"
    private const val CURRENT_VERSION = 3

    /**
     * Install assets if not already installed or if version changed.
     */
    fun installIfNeeded(context: Context) {
        val versionFile = File(context.filesDir, VERSION_FILE)
        val installedVersion = if (versionFile.exists()) {
            versionFile.readText().trim().toIntOrNull() ?: 0
        } else {
            0
        }

        if (installedVersion >= CURRENT_VERSION) {
            Log.d(TAG, "Assets already installed (v$installedVersion)")
            return
        }

        installAssets(context)
        versionFile.writeText(CURRENT_VERSION.toString())
        Log.i(TAG, "Assets installed (v$CURRENT_VERSION)")
    }

    /**
     * Install all assets from assets/bin/ to the app's files/bin/ directory.
     */
    private fun installAssets(context: Context) {
        val binDir = File(context.filesDir, ASSETS_BIN_DIR)
        if (!binDir.exists()) binDir.mkdirs()

        try {
            val assetFiles = context.assets.list(ASSETS_BIN_DIR) ?: return

            for (fileName in assetFiles) {
                try {
                    val inputFile = File(binDir, fileName)
                    context.assets.open("$ASSETS_BIN_DIR/$fileName").use { input ->
                        FileOutputStream(inputFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    // Make executable
                    try {
                        Runtime.getRuntime().exec(
                            arrayOf("chmod", "755", inputFile.absolutePath)
                        ).waitFor()
                    } catch (e: Exception) {
                        Log.w(TAG, "chmod failed for $fileName: ${e.message}")
                    }

                    Log.d(TAG, "Installed: $fileName")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to install: $fileName", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list assets", e)
        }
    }

    /**
     * Get the bin directory path.
     */
    fun getBinPath(context: Context): String {
        return File(context.filesDir, ASSETS_BIN_DIR).absolutePath
    }

    /**
     * List available commands.
     */
    fun listCommands(context: Context): List<String> {
        val binDir = File(context.filesDir, ASSETS_BIN_DIR)
        if (!binDir.exists()) return emptyList()
        return binDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("termx-") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }
}
