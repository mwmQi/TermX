package com.termx.app.pkg

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the TermX package repository — fetching, parsing, and caching
 * the package index from one or more remote repositories.
 *
 * Repository format:
 * The repository serves an index file (packages.json or packages.txt) containing
 * metadata for all available packages. The index is fetched over HTTPS and
 * cached locally for offline access.
 *
 * JSON format (packages.json):
 * ```json
 * {
 *   "version": 1,
 *   "arch": "aarch64",
 *   "packages": [
 *     {
 *       "name": "bash",
 *       "version": "5.2.15-1",
 *       "arch": "aarch64",
 *       "description": "Bourne Again Shell",
 *       "depends": ["readline", "ncurses"],
 *       "size": 5242880,
 *       "compressedSize": 1572864,
 *       "url": "https://repo.termx.app/pool/main/bash_5.2.15-1_aarch64.tar.gz",
 *       "sha256": "abc123...",
 *       "category": "shells",
 *       "license": "GPL-3.0",
 *       "homepage": "https://www.gnu.org/software/bash/"
 *     }
 *   ]
 * }
 * ```
 *
 * Text format (packages.txt):
 * Each line: name|version|arch|description|depends|size|compressedSize|url|sha256|category|license|homepage
 */
class Repository(private val context: Context) {

    companion object {
        private const val TAG = "Repository"

        /** Default repository URL */
        const val DEFAULT_REPO_URL = "https://repo.termx.app"

        /** Local cache file name for the package index */
        private const val INDEX_CACHE_FILE = "packages_index.json"

        /** Local file for installed packages database */
        private const val INSTALLED_DB_FILE = "installed_packages.json"

        /** Local file for repository sources list */
        private const val SOURCES_LIST_FILE = "sources.list"

        /** Maximum age of cached index before refresh (24 hours) */
        private const val INDEX_MAX_AGE_MS = 24 * 60 * 60 * 1000L

        /** Connection timeout for HTTP requests (15 seconds) */
        private const val CONNECT_TIMEOUT_MS = 15000

        /** Read timeout for HTTP requests (30 seconds) */
        private const val READ_TIMEOUT_MS = 30000
    }

    private val gson = Gson()
    private val prefsDir = File(context.filesDir, "pkg")
    private val cacheDir = File(prefsDir, "cache")
    private val etcDir = File(prefsDir, "etc")

    // In-memory package index
    private val packageIndex = ConcurrentHashMap<String, PackageInfo>()

    // Installed packages database
    private val installedPackages = ConcurrentHashMap<String, InstalledPackageRecord>()

    init {
        prefsDir.mkdirs()
        cacheDir.mkdirs()
        etcDir.mkdirs()
    }

    /**
     * Record of an installed package in the local database.
     */
    data class InstalledPackageRecord(
        val name: String,
        val version: String,
        val installTime: Long = System.currentTimeMillis(),
        val autoInstalled: Boolean = false  // true if installed as a dependency
    )

    // ---- Repository Sources ----

    /**
     * Get the list of repository URLs.
     */
    fun getSources(): List<String> {
        val sourcesFile = File(etcDir, SOURCES_LIST_FILE)
        if (!sourcesFile.exists()) {
            // Create default sources
            sourcesFile.writeText("# TermX Package Repository Sources\n" +
                "# One URL per line. Lines starting with # are comments.\n" +
                "$DEFAULT_REPO_URL\n")
        }

        return sourcesFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    /**
     * Add a repository source URL.
     */
    fun addSource(url: String) {
        val sources = getSources().toMutableList()
        if (url !in sources) {
            sources.add(url)
            saveSources(sources)
        }
    }

    /**
     * Remove a repository source URL.
     */
    fun removeSource(url: String) {
        val sources = getSources().toMutableList()
        sources.remove(url)
        saveSources(sources)
    }

    private fun saveSources(sources: List<String>) {
        File(etcDir, SOURCES_LIST_FILE).writeText(
            "# TermX Package Repository Sources\n" +
            sources.joinToString("\n") { it }
        )
    }

    // ---- Package Index ----

    /**
     * Fetch the package index from all configured repositories.
     * Updates the in-memory index and caches the result locally.
     *
     * @param forceRefresh If true, bypass the cache and fetch fresh data
     * @return Number of packages in the updated index
     */
    fun fetchIndex(forceRefresh: Boolean = false): Int {
        // Check cache validity
        val cacheFile = File(cacheDir, INDEX_CACHE_FILE)
        if (!forceRefresh && cacheFile.exists()) {
            val cacheAge = System.currentTimeMillis() - cacheFile.lastModified()
            if (cacheAge < INDEX_MAX_AGE_MS) {
                // Load from cache
                loadIndexFromCache()
                if (packageIndex.isNotEmpty()) {
                    Log.i(TAG, "Loaded ${packageIndex.size} packages from cache")
                    return packageIndex.size
                }
            }
        }

        // Fetch from network
        var totalFetched = 0
        for (repoUrl in getSources()) {
            try {
                val count = fetchRepoIndex(repoUrl)
                totalFetched += count
                Log.i(TAG, "Fetched $count packages from $repoUrl")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch from $repoUrl: ${e.message}")
            }
        }

        // Cache the result
        if (packageIndex.isNotEmpty()) {
            saveIndexToCache()
        }

        return totalFetched
    }

    /**
     * Fetch and parse the index from a single repository URL.
     */
    private fun fetchRepoIndex(repoUrl: String): Int {
        val indexUrl = "${repoUrl.trimEnd('/')}/packages.json"
        val connection = URL(indexUrl).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "TermX/1.0")

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $responseCode from $indexUrl")
            }

            val json = connection.inputStream.bufferedReader().readText()
            parseIndexJson(json)
        } finally {
            connection.disconnect()
        }

        return packageIndex.size
    }

    /**
     * Parse the JSON package index.
     */
    private fun parseIndexJson(json: String) {
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val root: Map<String, Any> = gson.fromJson(json, type)

        @Suppress("UNCHECKED_CAST")
        val packages = root["packages"] as? List<Map<String, Any>> ?: return

        for (pkgData in packages) {
            val name = pkgData["name"] as? String ?: continue
            val installedVersion = installedPackages[name]?.version
            val pkg = PackageInfo(
                name = name,
                version = pkgData["version"] as? String ?: "",
                arch = pkgData["arch"] as? String ?: "aarch64",
                description = pkgData["description"] as? String ?: "",
                depends = (pkgData["depends"] as? List<String>) ?: emptyList(),
                size = (pkgData["size"] as? Double)?.toLong() ?: 0,
                compressedSize = (pkgData["compressedSize"] as? Double)?.toLong() ?: 0,
                url = pkgData["url"] as? String ?: "",
                sha256 = pkgData["sha256"] as? String ?: "",
                category = pkgData["category"] as? String ?: "utils",
                license = pkgData["license"] as? String ?: "",
                homepage = pkgData["homepage"] as? String ?: "",
                installed = installedVersion != null,
                installedVersion = installedVersion
            )
            packageIndex[name] = pkg
        }
    }

    /**
     * Save the package index to a local JSON cache file.
     */
    private fun saveIndexToCache() {
        try {
            val cacheFile = File(cacheDir, INDEX_CACHE_FILE)
            val json = gson.toJson(mapOf(
                "version" to 1,
                "timestamp" to System.currentTimeMillis(),
                "packages" to packageIndex.values.toList()
            ))
            cacheFile.writeText(json)
            Log.d(TAG, "Package index cached (${packageIndex.size} packages)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache package index", e)
        }
    }

    /**
     * Load the package index from the local JSON cache.
     */
    private fun loadIndexFromCache() {
        try {
            val cacheFile = File(cacheDir, INDEX_CACHE_FILE)
            if (!cacheFile.exists()) return

            val json = cacheFile.readText()
            parseIndexJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached index", e)
        }
    }

    // ---- Package Queries ----

    /**
     * Get a package by name.
     */
    fun getPackage(name: String): PackageInfo? = packageIndex[name]

    /**
     * Search packages by name or description.
     */
    fun searchPackages(query: String): List<PackageInfo> {
        val lowerQuery = query.lowercase()
        return packageIndex.values.filter { pkg ->
            pkg.name.lowercase().contains(lowerQuery) ||
            pkg.description.lowercase().contains(lowerQuery)
        }.sorted()
    }

    /**
     * Get all available packages.
     */
    fun getAllPackages(): List<PackageInfo> = packageIndex.values.sorted()

    /**
     * Get all installed packages.
     */
    fun getInstalledPackages(): List<PackageInfo> =
        packageIndex.values.filter { it.installed }.sorted()

    /**
     * Get packages with available updates.
     */
    fun getUpgradablePackages(): List<PackageInfo> =
        packageIndex.values.filter { it.hasUpdate }

    /**
     * Get packages by category.
     */
    fun getPackagesByCategory(category: String): List<PackageInfo> =
        packageIndex.values.filter { it.category == category }.sorted()

    // ---- Download ----

    /**
     * Download a package archive to the local cache.
     *
     * @param pkg     The package to download
     * @param progress Callback with (bytesRead, totalBytes) for progress tracking
     * @return        The local File where the archive was saved, or null on failure
     */
    fun downloadPackage(
        pkg: PackageInfo,
        progress: ((read: Long, total: Long) -> Unit)? = null
    ): File? {
        val archiveFile = File(cacheDir, pkg.fileName)
        if (archiveFile.exists()) {
            // Verify SHA-256 if available
            if (pkg.sha256.isNotEmpty()) {
                val hash = sha256File(archiveFile)
                if (hash.equals(pkg.sha256, ignoreCase = true)) {
                    Log.i(TAG, "Using cached archive: ${pkg.fileName}")
                    return archiveFile
                } else {
                    Log.w(TAG, "Cached archive hash mismatch, re-downloading")
                    archiveFile.delete()
                }
            } else {
                return archiveFile
            }
        }

        try {
            val url = URL(pkg.url)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            val totalSize = connection.contentLengthLong.coerceAtLeast(pkg.compressedSize)

            connection.inputStream.use { input ->
                FileOutputStream(archiveFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        progress?.invoke(bytesRead, totalSize)
                    }
                }
            }

            connection.disconnect()

            // Verify download
            if (pkg.sha256.isNotEmpty()) {
                val hash = sha256File(archiveFile)
                if (!hash.equals(pkg.sha256, ignoreCase = true)) {
                    Log.e(TAG, "SHA-256 verification failed for ${pkg.fileName}")
                    archiveFile.delete()
                    return null
                }
            }

            Log.i(TAG, "Downloaded: ${pkg.fileName}")
            return archiveFile

        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${pkg.name}: ${e.message}")
            archiveFile.delete()
            return null
        }
    }

    // ---- Installed Packages DB ----

    /**
     * Load the installed packages database from disk.
     */
    fun loadInstalledDb() {
        val dbFile = File(prefsDir, INSTALLED_DB_FILE)
        if (!dbFile.exists()) return

        try {
            val json = dbFile.readText()
            val type = object : TypeToken<Map<String, InstalledPackageRecord>>() {}.type
            val records: Map<String, InstalledPackageRecord> = gson.fromJson(json, type)
            installedPackages.clear()
            installedPackages.putAll(records)

            // Update packageIndex installed status
            for ((name, record) in installedPackages) {
                packageIndex[name]?.let { pkg ->
                    packageIndex[name] = pkg.copy(
                        installed = true,
                        installedVersion = record.version
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load installed packages DB", e)
        }
    }

    /**
     * Save the installed packages database to disk.
     */
    fun saveInstalledDb() {
        try {
            val dbFile = File(prefsDir, INSTALLED_DB_FILE)
            val json = gson.toJson(installedPackages)
            dbFile.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save installed packages DB", e)
        }
    }

    /**
     * Mark a package as installed.
     */
    fun markInstalled(name: String, version: String, autoInstalled: Boolean = false) {
        installedPackages[name] = InstalledPackageRecord(
            name = name,
            version = version,
            autoInstalled = autoInstalled
        )
        packageIndex[name]?.let { pkg ->
            packageIndex[name] = pkg.copy(
                installed = true,
                installedVersion = version
            )
        }
        saveInstalledDb()
    }

    /**
     * Mark a package as uninstalled.
     */
    fun markUninstalled(name: String) {
        installedPackages.remove(name)
        packageIndex[name]?.let { pkg ->
            packageIndex[name] = pkg.copy(
                installed = false,
                installedVersion = null
            )
        }
        saveInstalledDb()
    }

    /**
     * Check if a package is installed.
     */
    fun isInstalled(name: String): Boolean = installedPackages.containsKey(name)

    // ---- Utility ----

    /**
     * Compute SHA-256 hash of a file.
     */
    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Clear the package index cache.
     */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        packageIndex.clear()
        Log.i(TAG, "Package index cache cleared")
    }

    /**
     * Get the cache directory for package archives.
     */
    fun getCacheDir(): File = cacheDir
}
