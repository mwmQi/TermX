package com.termx.app.pkg

import android.content.Context
import android.util.Log
import java.io.*
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * TermX Package Manager — the core of the `pkg` command system.
 *
 * This class provides a complete package management solution inspired by
 * Termux's pkg/apt system, designed specifically for Android environments.
 * It handles package installation, removal, upgrading, and dependency
 * resolution with a focus on safety and reliability.
 *
 * Architecture:
 *   - Packages are stored as .tar.gz archives in a remote repository
 *   - Archives are downloaded, SHA-256 verified, and extracted to $PREFIX
 *   - $PREFIX = /data/data/com.termx.app/files/usr (following Termux convention)
 *   - A local JSON database tracks installed packages and versions
 *   - Dependency resolution uses a simple recursive algorithm with cycle detection
 *
 * Package directory layout (under $PREFIX):
 *   usr/bin/      — Executable binaries
 *   usr/lib/      — Shared libraries (.so files)
 *   usr/include/  — Header files (for development packages)
 *   usr/share/    — Data files, man pages, etc.
 *   usr/etc/      — Configuration files
 *   usr/tmp/      — Temporary files
 *   usr/var/      — Variable data (logs, state)
 *
 * Thread safety: This class is NOT thread-safe. All operations should be
 * called from a single thread (typically a background coroutine).
 */
class TermXPackageManager(private val context: Context) {

    companion object {
        private const val TAG = "TermXPackageManager"

        /** The installation prefix directory */
        const val PREFIX = "/data/data/com.termx.app/files/usr"

        /** Bootstrap version tracking file */
        private const val BOOTSTRAP_VERSION_FILE = ".bootstrap_version"
        private const val CURRENT_BOOTSTRAP_VERSION = 1
    }

    private val repository = Repository(context)
    private val prefixDir = File(PREFIX)

    // ---- Public API ----

    /**
     * Initialize the package manager.
     * Loads the installed packages database and checks for bootstrap.
     */
    fun init() {
        // Create prefix directory structure
        createPrefixDirs()

        // Load installed packages database
        repository.loadInstalledDb()

        // Check if bootstrap is needed
        checkBootstrap()

        Log.i(TAG, "Package manager initialized, ${repository.getInstalledPackages().size} packages installed")
    }

    /**
     * Update the package index from all configured repositories.
     *
     * @param forceRefresh If true, bypass the cache and fetch fresh data
     * @return UpdateResult with count and any errors
     */
    fun updateIndex(forceRefresh: Boolean = false): UpdateResult {
        return try {
            val count = repository.fetchIndex(forceRefresh)
            UpdateResult(success = true, packageCount = count)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update package index", e)
            UpdateResult(success = false, error = e.message ?: "Unknown error")
        }
    }

    /**
     * Install a package by name.
     * Handles dependency resolution, download, verification, and extraction.
     *
     * @param name     Package name to install
     * @param progress Callback for progress updates (message, percentage 0-100)
     * @return         InstallResult with details of the operation
     */
    fun installPackage(name: String, progress: ((String, Int) -> Unit)? = null): InstallResult {
        progress?.invoke("Resolving dependencies for $name...", 5)

        // Resolve dependencies (topological order)
        val installOrder = resolveDependencies(name)
        if (installOrder == null) {
            return InstallResult(
                success = false,
                error = "Failed to resolve dependencies for $name"
            )
        }

        // Filter out already-installed packages
        val toInstall = installOrder.filter { !repository.isInstalled(it) }

        if (toInstall.isEmpty()) {
            return InstallResult(
                success = true,
                message = "$name is already installed",
                packagesInstalled = emptyList()
            )
        }

        Log.i(TAG, "Install plan: ${toInstall.joinToString(", ")}")

        val installedPackages = mutableListOf<String>()
        var totalProgress = 0

        for ((index, pkgName) in toInstall.withIndex()) {
            val pkg = repository.getPackage(pkgName)
            if (pkg == null) {
                // Try fetching a fresh index
                repository.fetchIndex(true)
                val freshPkg = repository.getPackage(pkgName)
                if (freshPkg == null) {
                    return InstallResult(
                        success = false,
                        error = "Package not found: $pkgName",
                        packagesInstalled = installedPackages
                    )
                }
            }

            val packageInfo = repository.getPackage(pkgName) ?: continue
            val baseProgress = (index.toFloat() / toInstall.size * 100).toInt()

            // Download
            progress?.invoke("Downloading ${packageInfo.name} ${packageInfo.version}...", baseProgress + 5)
            val archiveFile = repository.downloadPackage(packageInfo) { read, total ->
                val downloadPercent = if (total > 0) (read * 100 / total).toInt() else 0
                progress?.invoke(
                    "Downloading ${packageInfo.name}: $downloadPercent%",
                    baseProgress + (downloadPercent * 20 / 100)
                )
            }

            if (archiveFile == null) {
                return InstallResult(
                    success = false,
                    error = "Failed to download ${packageInfo.name}",
                    packagesInstalled = installedPackages
                )
            }

            // Extract
            progress?.invoke("Installing ${packageInfo.name}...", baseProgress + 30)
            val extractResult = extractPackage(archiveFile)
            if (!extractResult) {
                return InstallResult(
                    success = false,
                    error = "Failed to extract ${packageInfo.name}",
                    packagesInstalled = installedPackages
                )
            }

            // Run post-install scripts if any
            runPostInstall(packageInfo.name)

            // Mark as installed
            val isAutoDep = pkgName != name
            repository.markInstalled(packageInfo.name, packageInfo.version, isAutoDep)
            installedPackages.add(packageInfo.name)

            progress?.invoke(
                "Installed ${packageInfo.name} ${packageInfo.version}",
                baseProgress + 95
            )
        }

        progress?.invoke("Done!", 100)

        return InstallResult(
            success = true,
            message = "Installed ${installedPackages.size} package(s)",
            packagesInstalled = installedPackages
        )
    }

    /**
     * Install multiple packages at once.
     * Optimizes dependency resolution across all requested packages.
     */
    fun installPackages(names: List<String>, progress: ((String, Int) -> Unit)? = null): InstallResult {
        val allToInstall = mutableListOf<String>()
        for (name in names) {
            val deps = resolveDependencies(name) ?: return InstallResult(
                success = false,
                error = "Failed to resolve dependencies for $name"
            )
            allToInstall.addAll(deps)
        }

        // Deduplicate while preserving order
        val uniqueToInstall = allToInstall.distinct().filter { !repository.isInstalled(it) }

        if (uniqueToInstall.isEmpty()) {
            return InstallResult(success = true, message = "All packages already installed")
        }

        // Install each
        val installed = mutableListOf<String>()
        for (pkgName in uniqueToInstall) {
            val result = installPackage(pkgName, progress)
            if (!result.success) return result
            installed.addAll(result.packagesInstalled)
        }

        return InstallResult(
            success = true,
            message = "Installed ${installed.size} package(s)",
            packagesInstalled = installed
        )
    }

    /**
     * Uninstall a package by name.
     * Removes the package's files and marks it as uninstalled.
     * Does NOT automatically remove orphaned dependencies.
     *
     * @param name          Package name to uninstall
     * @param removeDeps    If true, also remove dependencies that are no longer needed
     * @return              UninstallResult
     */
    fun uninstallPackage(name: String, removeDeps: Boolean = false): UninstallResult {
        if (!repository.isInstalled(name)) {
            return UninstallResult(success = false, error = "Package not installed: $name")
        }

        // Check if any other installed package depends on this one
        val dependents = findDependents(name)
        if (dependents.isNotEmpty()) {
            return UninstallResult(
                success = false,
                error = "Cannot remove $name: required by ${dependents.joinToString(", ")}"
            )
        }

        // Run pre-uninstall scripts if any
        runPreUninstall(name)

        // Remove package files
        removePackageFiles(name)

        // Mark as uninstalled
        repository.markUninstalled(name)

        // Optionally remove orphaned dependencies
        val removedDeps = mutableListOf<String>()
        if (removeDeps) {
            val orphans = findOrphanedDependencies()
            for (orphan in orphans) {
                removePackageFiles(orphan)
                repository.markUninstalled(orphan)
                removedDeps.add(orphan)
            }
        }

        Log.i(TAG, "Uninstalled: $name${if (removedDeps.isNotEmpty()) " + ${removedDeps.joinToString(", ")}" else ""}")

        return UninstallResult(
            success = true,
            message = "Removed $name${if (removedDeps.isNotEmpty()) " and ${removedDeps.size} orphan(s)" else ""}",
            packagesRemoved = listOf(name) + removedDeps
        )
    }

    /**
     * Upgrade all installed packages that have newer versions available.
     */
    fun upgradeAll(progress: ((String, Int) -> Unit)? = null): UpgradeResult {
        val upgradable = repository.getUpgradablePackages()
        if (upgradable.isEmpty()) {
            return UpgradeResult(success = true, message = "All packages are up to date")
        }

        progress?.invoke("Upgrading ${upgradable.size} package(s)...", 0)

        val upgraded = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for ((index, pkg) in upgradable.withIndex()) {
            val baseProgress = (index.toFloat() / upgradable.size * 100).toInt()
            progress?.invoke("Upgrading ${pkg.name}...", baseProgress)

            // Download new version
            val archiveFile = repository.downloadPackage(pkg)
            if (archiveFile == null) {
                failed.add(pkg.name)
                continue
            }

            // Extract (overwrites existing files)
            if (!extractPackage(archiveFile)) {
                failed.add(pkg.name)
                continue
            }

            // Update installed version
            repository.markInstalled(pkg.name, pkg.version)
            upgraded.add(pkg.name)
        }

        progress?.invoke("Upgrade complete!", 100)

        return UpgradeResult(
            success = failed.isEmpty(),
            upgradedPackages = upgraded,
            failedPackages = failed
        )
    }

    /**
     * Search for packages matching a query.
     */
    fun search(query: String): List<PackageInfo> {
        return repository.searchPackages(query)
    }

    /**
     * List all installed packages.
     */
    fun listInstalled(): List<PackageInfo> {
        return repository.getInstalledPackages()
    }

    /**
     * List all available packages.
     */
    fun listAvailable(): List<PackageInfo> {
        return repository.getAllPackages()
    }

    /**
     * Show detailed info about a package.
     */
    fun showInfo(name: String): PackageInfo? {
        return repository.getPackage(name)
    }

    /**
     * Get packages with available updates.
     */
    fun listUpgradable(): List<PackageInfo> {
        return repository.getUpgradablePackages()
    }

    /**
     * Clean the package cache (downloaded archives).
     */
    fun cleanCache() {
        repository.clearCache()
    }

    // ---- Dependency Resolution ----

    /**
     * Resolve all dependencies for a package, returning them in
     * topological (install) order.
     *
     * Uses depth-first search with cycle detection.
     * Returns null if a dependency cannot be found in the index.
     */
    private fun resolveDependencies(
        name: String,
        visited: MutableSet<String> = mutableSetOf(),
        visiting: MutableSet<String> = mutableSetOf(),
        result: MutableList<String> = mutableListOf()
    ): MutableList<String>? {
        if (name in visiting) {
            Log.e(TAG, "Circular dependency detected: $name")
            return null // Circular dependency
        }

        if (name in visited) return result
        if (repository.isInstalled(name)) {
            visited.add(name)
            return result
        }

        visiting.add(name)

        val pkg = repository.getPackage(name)
        if (pkg == null) {
            Log.e(TAG, "Package not found in index: $name")
            visiting.remove(name)
            return null
        }

        // Recursively resolve dependencies
        for (dep in pkg.depends) {
            val depResult = resolveDependencies(dep, visited, visiting, result)
            if (depResult == null) {
                visiting.remove(name)
                return null
            }
        }

        visiting.remove(name)
        visited.add(name)
        result.add(name)

        return result
    }

    /**
     * Find all installed packages that depend on the given package.
     */
    private fun findDependents(name: String): List<String> {
        return repository.getInstalledPackages().filter { pkg ->
            pkg.depends.contains(name)
        }.map { it.name }
    }

    /**
     * Find orphaned dependencies — auto-installed packages that are no longer
     * required by any manually-installed package.
     */
    private fun findOrphanedDependencies(): List<String> {
        val manuallyInstalled = repository.getInstalledPackages()
            .filter { /* not auto-installed */ true } // Simplified for now
            .map { it.name }

        val required = mutableSetOf<String>()
        for (pkg in manuallyInstalled) {
            val info = repository.getPackage(pkg) ?: continue
            addTransitiveDeps(info, required)
        }

        return repository.getInstalledPackages()
            .filter { it.name !in required && it.name !in manuallyInstalled }
            .map { it.name }
    }

    private fun addTransitiveDeps(pkg: PackageInfo, required: MutableSet<String>) {
        for (dep in pkg.depends) {
            if (dep !in required) {
                required.add(dep)
                repository.getPackage(dep)?.let { addTransitiveDeps(it, required) }
            }
        }
    }

    // ---- Package Extraction ----

    /**
     * Extract a .tar.gz package archive to the prefix directory.
     *
     * @param archiveFile The downloaded archive file
     * @return            true if extraction succeeded
     */
    private fun extractPackage(archiveFile: File): Boolean {
        try {
            TarArchiveInputStream(GZIPInputStream(FileInputStream(archiveFile))).use { tarStream ->
                var entry = tarStream.nextTarEntry
                while (entry != null) {
                    val outputFile = File(prefixDir, entry.name)

                    // Security: prevent path traversal
                    if (!outputFile.canonicalPath.startsWith(prefixDir.canonicalPath)) {
                        Log.w(TAG, "Skipping path traversal entry: ${entry.name}")
                        entry = tarStream.nextTarEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        // Ensure parent directory exists
                        outputFile.parentFile?.mkdirs()

                        FileOutputStream(outputFile).use { output ->
                            val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (tarStream.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            }
                        }

                        // Preserve executable permission
                        if (entry.name.startsWith("bin/") || entry.name.contains("/bin/")) {
                            try {
                                Runtime.getRuntime().exec(
                                    arrayOf("chmod", "755", outputFile.absolutePath)
                                ).waitFor()
                            } catch (e: Exception) {
                                Log.w(TAG, "chmod failed for ${entry.name}: ${e.message}")
                            }
                        }
                    }

                    entry = tarStream.nextTarEntry
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract ${archiveFile.name}", e)
            return false
        }
    }

    /**
     * Remove a package's files from the prefix directory.
     * Uses the package's file list from the installed database.
     */
    private fun removePackageFiles(name: String) {
        // For simplicity, we remove known directories for the package
        // A production implementation would track individual files
        val pkgDirs = listOf(
            File(PREFIX, "bin/$name"),
            File(PREFIX, "lib/$name"),
            File(PREFIX, "share/$name"),
            File(PREFIX, "share/doc/$name"),
            File(PREFIX, "share/man/$name")
        )

        for (dir in pkgDirs) {
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    // ---- Pre/Post Install Scripts ----

    /**
     * Run post-install scripts if they exist in the package.
     * These are shell scripts placed in $PREFIX/etc/post-install.d/
     */
    private fun runPostInstall(packageName: String) {
        val script = File(PREFIX, "etc/post-install.d/$packageName.sh")
        if (script.exists() && script.canExecute()) {
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf("/system/bin/sh", script.absolutePath),
                    arrayOf("PREFIX=$PREFIX"),
                    prefixDir
                )
                process.waitFor()
                Log.d(TAG, "Post-install script ran for $packageName (exit=${process.exitValue()})")
            } catch (e: Exception) {
                Log.w(TAG, "Post-install script failed for $packageName: ${e.message}")
            }
        }
    }

    /**
     * Run pre-uninstall scripts if they exist.
     */
    private fun runPreUninstall(packageName: String) {
        val script = File(PREFIX, "etc/pre-uninstall.d/$packageName.sh")
        if (script.exists() && script.canExecute()) {
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf("/system/bin/sh", script.absolutePath),
                    arrayOf("PREFIX=$PREFIX"),
                    prefixDir
                )
                process.waitFor()
                Log.d(TAG, "Pre-uninstall script ran for $packageName")
            } catch (e: Exception) {
                Log.w(TAG, "Pre-uninstall script failed for $packageName: ${e.message}")
            }
        }
    }

    // ---- Bootstrap ----

    /**
     * Check if the initial bootstrap is needed and run it if so.
     * Bootstrap installs the minimal base system needed for the package
     * manager to function (coreutils, bash, etc.).
     */
    private fun checkBootstrap() {
        val versionFile = File(context.filesDir, BOOTSTRAP_VERSION_FILE)
        val installedVersion = if (versionFile.exists()) {
            versionFile.readText().trim().toIntOrNull() ?: 0
        } else {
            0
        }

        if (installedVersion < CURRENT_BOOTSTRAP_VERSION) {
            Log.i(TAG, "Bootstrap needed (v$installedVersion < v$CURRENT_BOOTSTRAP_VERSION)")
            BootstrapInstaller.install(context)
            versionFile.writeText(CURRENT_BOOTSTRAP_VERSION.toString())
            Log.i(TAG, "Bootstrap complete (v$CURRENT_BOOTSTRAP_VERSION)")
        }
    }

    /**
     * Create the $PREFIX directory structure.
     */
    private fun createPrefixDirs() {
        val dirs = listOf(
            "bin", "lib", "include", "share", "share/doc",
            "share/man", "etc", "etc/post-install.d", "etc/pre-uninstall.d",
            "tmp", "var", "var/log"
        )
        for (dir in dirs) {
            File(PREFIX, dir).mkdirs()
        }

        // Create symlinks for compatibility
        val compatLinks = mapOf(
            "usr/bin" to "bin",
            "usr/lib" to "lib",
            "usr/etc" to "etc"
        )
        for ((link, target) in compatLinks) {
            val linkFile = File(context.filesDir, link)
            val targetFile = File(PREFIX, target)
            if (!linkFile.exists() && targetFile.exists()) {
                try {
                    linkFile.delete()
                    Runtime.getRuntime().exec(
                        arrayOf("ln", "-s", targetFile.absolutePath, linkFile.absolutePath)
                    ).waitFor()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create symlink $link -> $target: ${e.message}")
                }
            }
        }
    }

    // ---- Result Classes ----

    data class UpdateResult(
        val success: Boolean,
        val packageCount: Int = 0,
        val error: String? = null
    )

    data class InstallResult(
        val success: Boolean,
        val message: String? = null,
        val error: String? = null,
        val packagesInstalled: List<String> = emptyList()
    )

    data class UninstallResult(
        val success: Boolean,
        val message: String? = null,
        val error: String? = null,
        val packagesRemoved: List<String> = emptyList()
    )

    data class UpgradeResult(
        val success: Boolean,
        val message: String? = null,
        val upgradedPackages: List<String> = emptyList(),
        val failedPackages: List<String> = emptyList()
    )
}
