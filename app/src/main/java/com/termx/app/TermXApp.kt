package com.termx.app

import android.app.Application
import com.termx.app.config.ConfigDirectory
import com.termx.app.pkg.TermXPackageManager
import com.termx.app.utils.AssetInstaller

class TermXApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this

        // Install shell command wrappers on first run
        AssetInstaller.installIfNeeded(this)

        // Initialize config directory structure
        ConfigDirectory.init(this)

        // Initialize package manager (creates $PREFIX dirs, runs bootstrap)
        Thread {
            try {
                val pkgManager = TermXPackageManager(this)
                pkgManager.init()
            } catch (e: Exception) {
                android.util.Log.e("TermXApp", "Package manager init failed", e)
            }
        }.start()
    }

    companion object {
        lateinit var instance: TermXApp
            private set

        /**
         * Get the package manager instance.
         * Should be called from a background thread for operations that
         * involve network access or file I/O.
         */
        @Volatile
        private var pkgManager: TermXPackageManager? = null

        fun getPackageManager(): TermXPackageManager {
            return pkgManager ?: synchronized(this) {
                pkgManager ?: TermXPackageManager(instance).also {
                    pkgManager = it
                }
            }
        }
    }
}
