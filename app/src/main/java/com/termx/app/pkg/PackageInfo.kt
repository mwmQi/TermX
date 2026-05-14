package com.termx.app.pkg

/**
 * Represents a single package in the TermX package repository.
 *
 * Each package corresponds to a compiled binary distribution (typically
 * a .deb or .tar.gz archive) that can be installed into the TermX
 * $PREFIX (/data/data/com.termx.app/files/usr) directory tree.
 *
 * Packages follow a naming convention similar to Debian/Termux:
 *   name_version_arch.tar.gz
 *   Example: bash_5.2.15-1_aarch64.tar.gz
 *
 * @property name         Package name (e.g., "bash", "coreutils", "python")
 * @property version      Package version string (e.g., "5.2.15-1")
 * @property arch         Target architecture (e.g., "aarch64", "arm", "x86_64")
 * @property description  Short human-readable description
 * @property depends      List of package names this package depends on
 * @property size         Installed size in bytes
 * @property compressedSize Compressed archive size in bytes
 * @property url          Download URL for the package archive
 * @property sha256       SHA-256 hash of the archive for verification
 * @property installed    Whether this package is currently installed
 * @property installedVersion Version of the installed package (null if not installed)
 * @property category     Package category (e.g., "shells", "utils", "dev")
 * @property license      License identifier (e.g., "GPL-3.0", "MIT")
 * @property homepage     Upstream project homepage URL
 */
data class PackageInfo(
    val name: String,
    val version: String,
    val arch: String = "aarch64",
    val description: String = "",
    val depends: List<String> = emptyList(),
    val size: Long = 0,
    val compressedSize: Long = 0,
    val url: String = "",
    val sha256: String = "",
    val installed: Boolean = false,
    val installedVersion: String? = null,
    val category: String = "utils",
    val license: String = "",
    val homepage: String = ""
) : Comparable<PackageInfo> {

    /**
     * The filename of the package archive.
     * Format: name_version_arch.tar.gz
     */
    val fileName: String
        get() = "${name}_${version}_${arch}.tar.gz"

    /**
     * Check if this package has unmet dependencies.
     */
    val hasUnmetDependencies: Boolean
        get() = depends.isNotEmpty() && !installed

    /**
     * Check if an update is available (installed but newer version exists).
     */
    val hasUpdate: Boolean
        get() = installed && installedVersion != null && installedVersion != version

    override fun compareTo(other: PackageInfo): Int = name.compareTo(other.name)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PackageInfo) return false
        return name == other.name
    }

    override fun hashCode(): Int = name.hashCode()

    /**
     * Parse a PackageInfo from a repo index line.
     * Format: name|version|arch|description|depends|size|compressedSize|url|sha256|category|license|homepage
     */
    companion object {
        fun fromRepoLine(line: String, installedVersion: String? = null): PackageInfo? {
            val parts = line.split("|")
            if (parts.size < 5) return null

            return PackageInfo(
                name = parts[0].trim(),
                version = parts[1].trim(),
                arch = parts.getOrElse(2) { "aarch64" }.trim(),
                description = parts.getOrElse(3) { "" }.trim(),
                depends = parts.getOrElse(4) { "" }.trim()
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() },
                size = parts.getOrElse(5) { "0" }.trim().toLongOrNull() ?: 0,
                compressedSize = parts.getOrElse(6) { "0" }.trim().toLongOrNull() ?: 0,
                url = parts.getOrElse(7) { "" }.trim(),
                sha256 = parts.getOrElse(8) { "" }.trim(),
                category = parts.getOrElse(9) { "utils" }.trim(),
                license = parts.getOrElse(10) { "" }.trim(),
                homepage = parts.getOrElse(11) { "" }.trim(),
                installed = installedVersion != null,
                installedVersion = installedVersion
            )
        }
    }
}
