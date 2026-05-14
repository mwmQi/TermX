package com.termx.app.power.ssh

import android.content.Context
import android.util.Log
import java.io.*
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

/**
 * SFTP subsystem implementation for the TermX SSH server.
 *
 * Implements the SFTP protocol (SSH File Transfer Protocol) as defined in
 * draft-ietf-secsh-filexfer-02 (version 3), which is the most widely
 * deployed version and is compatible with OpenSSH, WinSCP, FileZilla,
 * and all major SFTP clients.
 *
 * ## Protocol Overview
 *
 * SFTP operates as a subsystem within an SSH channel. The protocol uses
 * a simple request/response model:
 *
 *   Client -> Server:  Request packet (type + request-id + parameters)
 *   Server -> Client:  Response packet (type + request-id + results)
 *
 * ## Supported Operations
 *
 * File operations:
 *   - **OPEN** / **CLOSE**: Open and close file handles
 *   - **READ** / **WRITE**: Read and write file data
 *   - **REMOVE**: Delete a file
 *   - **RENAME**: Rename or move a file
 *   - **STAT** / **LSTAT** / **FSTAT**: Get file attributes
 *   - **SETSTAT** / **FSETSTAT**: Modify file attributes (best-effort on Android)
 *
 * Directory operations:
 *   - **OPENDIR**: Open a directory for listing
 *   - **READDIR**: Read directory entries (batched)
 *   - **MKDIR** / **RMDIR**: Create and remove directories
 *
 * Path operations:
 *   - **REALPATH**: Resolve a path to its absolute form
 *   - **READLINK**: Read symbolic link target (limited on Android)
 *   - **SYMLINK**: Create a symbolic link (limited on Android)
 *
 * ## Path Mapping
 *
 * SFTP paths are mapped to the Android filesystem using configurable roots:
 *
 *   | SFTP Path        | Android Path                              |
 *   |------------------|-------------------------------------------|
 *   | /                | / (system root, restricted)               |
 *   | /home            | /data/data/com.termx.app/files            |
 *   | /home/user       | /data/data/com.termx.app/files            |
 *   | /sdcard          | /sdcard or /storage/emulated/0            |
 *   | /storage         | /storage                                  |
 *   | /data            | /data/data/com.termx.app/files            |
 *   | /tmp             | /data/data/com.termx.app/files/usr/tmp    |
 *   | /prefix          | /data/data/com.termx.app/files/usr        |
 *
 * The path mapping ensures SFTP clients see a familiar Unix-like filesystem
 * while the actual paths are rooted in TermX's private directory.
 *
 * ## Security
 *
 * - File access is constrained to TermX's accessible paths
 * - Symlink escape prevention: resolved paths are checked against allowed roots
 * - Permission checks based on Android's file system permissions
 * - Sensitive directories (other app data) are not accessible
 *
 * @param context Android context for accessing the filesystem
 */
class SftpSubsystem(private val context: Context) {

    companion object {
        private const val TAG = "SftpSubsystem"

        /** SFTP protocol version supported */
        const val SFTP_VERSION = 3

        /** Maximum read size per request */
        const val MAX_READ_SIZE = 65536

        /** Maximum number of directory entries per READDIR response */
        const val MAX_DIR_ENTRIES_PER_READ = 100

        /** Maximum path length */
        const val MAX_PATH_LENGTH = 4096

        // SFTP packet types
        const val SSH_FXP_INIT = 1
        const val SSH_FXP_VERSION = 2
        const val SSH_FXP_OPEN = 3
        const val SSH_FXP_CLOSE = 4
        const val SSH_FXP_READ = 5
        const val SSH_FXP_WRITE = 6
        const val SSH_FXP_LSTAT = 7
        const val SSH_FXP_FSTAT = 8
        const val SSH_FXP_SETSTAT = 9
        const val SSH_FXP_FSETSTAT = 10
        const val SSH_FXP_OPENDIR = 11
        const val SSH_FXP_READDIR = 12
        const val SSH_FXP_REMOVE = 13
        const val SSH_FXP_MKDIR = 14
        const val SSH_FXP_RMDIR = 15
        const val SSH_FXP_REALPATH = 16
        const val SSH_FXP_STAT = 17
        const val SSH_FXP_RENAME = 18
        const val SSH_FXP_READLINK = 19
        const val SSH_FXP_SYMLINK = 20
        const val SSH_FXP_STATUS = 101
        const val SSH_FXP_HANDLE = 102
        const val SSH_FXP_DATA = 103
        const val SSH_FXP_NAME = 104
        const val SSH_FXP_ATTRS = 105
        const val SSH_FXP_EXTENDED = 200
        const val SSH_FXP_EXTENDED_REPLY = 201

        // SFTP status codes
        const val SSH_FX_OK = 0
        const val SSH_FX_EOF = 1
        const val SSH_FX_NO_SUCH_FILE = 2
        const val SSH_FX_PERMISSION_DENIED = 3
        const val SSH_FX_FAILURE = 4
        const val SSH_FX_BAD_MESSAGE = 5
        const val SSH_FX_NO_CONNECTION = 6
        const val SSH_FX_CONNECTION_LOST = 7
        const val SSH_FX_OP_UNSUPPORTED = 8

        // File open flags
        const val SSH_FXF_READ = 0x00000001
        const val SSH_FXF_WRITE = 0x00000002
        const val SSH_FXF_APPEND = 0x00000004
        const val SSH_FXF_CREAT = 0x00000008
        const val SSH_FXF_TRUNC = 0x00000010
        const val SSH_FXF_EXCL = 0x00000020

        // File attribute flags
        const val SSH_FILEXFER_ATTR_SIZE = 0x00000001
        const val SSH_FILEXFER_ATTR_UIDGID = 0x00000002
        const val SSH_FILEXFER_ATTR_PERMISSIONS = 0x00000004
        const val SSH_FILEXFER_ATTR_ACMODTIME = 0x00000008
        const val SSH_FILEXFER_ATTR_EXTENDED = -2147483648  // 0x80000000 as signed Int

        // File types (for permissions field)
        const val S_IFMT = 0xF000
        const val S_IFDIR = 0x4000
        const val S_IFREG = 0x8000
        const val S_IFLNK = 0xA000

        // Permission bits
        const val S_IRUSR = 0x0100
        const val S_IWUSR = 0x0080
        const val S_IXUSR = 0x0040
        const val S_IRGRP = 0x0020
        const val S_IWGRP = 0x0010
        const val S_IXGRP = 0x0008
        const val S_IROTH = 0x0004
        const val S_IWOTH = 0x0002
        const val S_IXOTH = 0x0001

        /** TermX's data directory */
        private const val TERMX_DATA_DIR = "/data/data/com.termx.app/files"

        /** TermX's PREFIX (where packages are installed) */
        private const val TERMX_PREFIX = "$TERMX_DATA_DIR/usr"
    }

    /**
     * Represents a file entry for SFTP NAME responses.
     */
    data class FileEntry(
        val path: String,
        val longName: String,
        val size: Long,
        val uid: Int,
        val gid: Int,
        val permissions: Int,
        val lastModified: Long = System.currentTimeMillis()
    )

    /**
     * Represents file attributes for SFTP ATTR responses.
     */
    data class FileAttributes(
        val size: Long = 0,
        val uid: Int = 0,
        val gid: Int = 0,
        val permissions: Int = 0,
        val atime: Int = 0,
        val mtime: Int = 0,
        val flags: Int = 0,
        val extended: Map<String, String> = emptyMap()
    )

    /**
     * Represents an open file handle.
     */
    data class OpenHandle(
        val id: String,
        val path: String,
        val file: File,
        val randomAccessFile: RandomAccessFile? = null,
        val isOpen: Boolean = true,
        val isDirectory: Boolean = false,
        val entries: List<File>? = null,
        var entryPosition: Int = 0,
        val openFlags: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    )

    // ---- State ----

    /** Active file handles keyed by handle ID */
    private val handles = java.util.concurrent.ConcurrentHashMap<String, OpenHandle>()

    /** Handle counter */
    private var handleCounter = 0L

    /** Whether access outside TermX directories is allowed */
    @Volatile var allowExternalAccess: Boolean = false

    /** Virtual root directory — SFTP "/" maps here */
    @Volatile var virtualRoot: String = TERMX_DATA_DIR

    /** List of allowed root paths for access control */
    private val allowedRoots = mutableListOf(
        TERMX_DATA_DIR,
        "/sdcard",
        "/storage/emulated/0",
        "/storage",
        "/tmp",
        TERMX_PREFIX
    )

    // ---- Path Resolution ----

    /**
     * Resolve an SFTP path to an absolute Android filesystem path.
     *
     * Path mapping rules:
     *   - Relative paths are resolved relative to virtualRoot
     *   - "/home" -> TERMX_DATA_DIR (user's home)
     *   - "/sdcard" -> /sdcard or /storage/emulated/0
     *   - "/tmp" -> TERMX_DATA_DIR/usr/tmp
     *   - "/prefix" -> TERMX_PREFIX
     *   - Other paths are resolved relative to virtualRoot
     *
     * @param sftpPath The path as seen by the SFTP client
     * @return The resolved absolute path on the Android filesystem
     */
    fun resolvePath(sftpPath: String): String {
        if (sftpPath.isEmpty()) return virtualRoot

        // Normalize the path (remove . and .. components, double slashes, etc.)
        val normalized = normalizePath(sftpPath)

        // Apply virtual path mappings
        val resolved = when {
            // Home directory mapping
            normalized == "/home" || normalized == "/home/" -> TERMX_DATA_DIR
            normalized.startsWith("/home/") -> TERMX_DATA_DIR + normalized.removePrefix("/home")
            normalized == "/home/user" || normalized == "/home/user/" -> TERMX_DATA_DIR
            normalized.startsWith("/home/user/") -> TERMX_DATA_DIR + normalized.removePrefix("/home/user")

            // SDCard mapping
            normalized == "/sdcard" || normalized == "/sdcard/" -> findSdcardPath()
            normalized.startsWith("/sdcard/") -> findSdcardPath() + normalized.removePrefix("/sdcard")

            // Storage mapping
            normalized == "/storage" || normalized == "/storage/" -> "/storage"
            normalized.startsWith("/storage/") -> normalized

            // Temp directory mapping
            normalized == "/tmp" || normalized == "/tmp/" -> "$TERMX_PREFIX/tmp"
            normalized.startsWith("/tmp/") -> "$TERMX_PREFIX/tmp" + normalized.removePrefix("/tmp")

            // Prefix mapping
            normalized == "/prefix" || normalized == "/prefix/" -> TERMX_PREFIX
            normalized.startsWith("/prefix/") -> TERMX_PREFIX + normalized.removePrefix("/prefix")

            // Data directory mapping
            normalized == "/data" || normalized == "/data/" -> TERMX_DATA_DIR
            normalized.startsWith("/data/") -> {
                // Allow access under TermX's data directory, but restrict other app data
                val rest = normalized.removePrefix("/data/")
                if (rest.startsWith("data/com.termx.app/") || rest == "data/com.termx.app") {
                    "/data/$rest"
                } else {
                    TERMX_DATA_DIR + "/" + rest
                }
            }

            // Absolute path with explicit virtual root mapping
            normalized.startsWith("/") -> {
                if (allowExternalAccess) {
                    normalized
                } else {
                    // Check if the path is within an allowed root
                    val allowed = allowedRoots.any { root ->
                        normalized == root || normalized.startsWith("$root/")
                    }
                    if (allowed) {
                        normalized
                    } else {
                        // Default: map to virtualRoot
                        virtualRoot + normalized
                    }
                }
            }

            // Relative path
            else -> "$virtualRoot/$normalized"
        }

        return resolveCanonicalPath(resolved)
    }

    /**
     * Reverse-map an Android filesystem path to an SFTP path.
     * Used for presenting paths in directory listings.
     *
     * @param realPath The actual path on the Android filesystem
     * @return The path as it should appear to the SFTP client
     */
    fun reverseMapPath(realPath: String): String {
        return when {
            realPath == TERMX_DATA_DIR -> "/home"
            realPath.startsWith("$TERMX_DATA_DIR/") -> {
                val relative = realPath.removePrefix("$TERMX_DATA_DIR/")
                "/home/$relative"
            }
            realPath.startsWith(findSdcardPath()) -> {
                val relative = realPath.removePrefix(findSdcardPath())
                "/sdcard$relative"
            }
            realPath.startsWith("$TERMX_PREFIX/tmp") -> {
                val relative = realPath.removePrefix("$TERMX_PREFIX/tmp")
                "/tmp$relative"
            }
            realPath.startsWith(TERMX_PREFIX) -> {
                val relative = realPath.removePrefix(TERMX_PREFIX)
                "/prefix$relative"
            }
            else -> realPath
        }
    }

    /**
     * Normalize a path by removing . and .. components and redundant slashes.
     */
    private fun normalizePath(path: String): String {
        if (path.isEmpty()) return "/"

        val components = path.split("/").filter { it.isNotEmpty() && it != "." }
        val result = mutableListOf<String>()

        for (component in components) {
            when (component) {
                ".." -> {
                    if (result.isNotEmpty()) {
                        result.removeAt(result.lastIndex)
                    }
                }
                else -> result.add(component)
            }
        }

        return "/" + result.joinToString("/")
    }

    /**
     * Resolve a path to its canonical form, handling symlinks.
     */
    private fun resolveCanonicalPath(path: String): String {
        return try {
            val file = File(path)
            if (file.exists()) {
                file.canonicalPath
            } else {
                // File doesn't exist yet — resolve the parent and append the name
                val parent = file.parentFile
                val name = file.name
                if (parent != null && parent.exists()) {
                    "${parent.canonicalPath}/$name"
                } else {
                    path
                }
            }
        } catch (e: Exception) {
            path
        }
    }

    /**
     * Find the actual sdcard path on this device.
     */
    private fun findSdcardPath(): String {
        // Check common sdcard paths
        val candidates = listOf("/sdcard", "/storage/emulated/0", "/mnt/sdcard")
        for (candidate in candidates) {
            if (File(candidate).exists()) return candidate
        }
        // Fallback
        return System.getenv("EXTERNAL_STORAGE") ?: "/sdcard"
    }

    // ---- Path Security ----

    /**
     * Check if a resolved path is within the allowed access scope.
     *
     * @param resolvedPath The canonical absolute path to check
     * @return true if access is allowed
     */
    fun isPathAccessible(resolvedPath: String): Boolean {
        if (allowExternalAccess) return true

        // Always allow TermX's own directories
        if (resolvedPath.startsWith(TERMX_DATA_DIR)) return true

        // Allow sdcard and storage
        for (root in allowedRoots) {
            if (resolvedPath == root || resolvedPath.startsWith("$root/")) {
                return true
            }
        }

        // Deny access to other app's data
        if (resolvedPath.startsWith("/data/data/") &&
            !resolvedPath.startsWith("/data/data/com.termx.app/")) {
            return false
        }

        // Deny access to system directories
        val deniedPaths = listOf("/system/app", "/system/priv-app", "/data/system")
        for (denied in deniedPaths) {
            if (resolvedPath.startsWith(denied)) return false
        }

        return false
    }

    /**
     * Validate that a path is safe and accessible.
     *
     * @param sftpPath The SFTP path to validate
     * @return The resolved safe path, or null if access is denied
     */
    fun validateAndResolvePath(sftpPath: String): String? {
        val resolved = resolvePath(sftpPath)
        return if (isPathAccessible(resolved)) resolved else null
    }

    // ---- File Handle Management ----

    /**
     * Generate a unique file handle ID.
     */
    private fun generateHandleId(): String {
        return String.format("h%016x", ++handleCounter)
    }

    /**
     * Open a file and return a handle.
     *
     * @param path The resolved filesystem path
     * @param flags Open flags (SSH_FXF_READ, SSH_FXF_WRITE, etc.)
     * @return OpenHandle or null if the open failed
     */
    fun openFile(path: String, flags: Int): OpenHandle? {
        val file = File(path)

        try {
            val mode = buildString {
                if ((flags and SSH_FXF_READ) != 0) append('r')
                if ((flags and SSH_FXF_WRITE) != 0) append('w')
            }.ifEmpty { "r" }

            if (mode.contains('w') && !file.exists()) {
                if ((flags and SSH_FXF_CREAT) != 0) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                } else {
                    return null
                }
            }

            if (!file.exists()) return null

            val raf = RandomAccessFile(file, if (mode.contains('w')) "rw" else "r")

            if ((flags and SSH_FXF_TRUNC) != 0) {
                raf.setLength(0)
            }
            if ((flags and SSH_FXF_APPEND) != 0) {
                raf.seek(raf.length())
            }

            val handle = OpenHandle(
                id = generateHandleId(),
                path = path,
                file = file,
                randomAccessFile = raf,
                openFlags = flags
            )
            handles[handle.id] = handle
            return handle

        } catch (e: Exception) {
            Log.w(TAG, "Failed to open file: $path", e)
            return null
        }
    }

    /**
     * Open a directory and return a handle.
     *
     * @param path The resolved filesystem path
     * @return OpenHandle with directory entries, or null if failed
     */
    fun openDirectory(path: String): OpenHandle? {
        val dir = File(path)

        if (!dir.exists() || !dir.isDirectory) return null

        val entries = dir.listFiles()?.toList() ?: emptyList()

        val handle = OpenHandle(
            id = generateHandleId(),
            path = path,
            file = dir,
            isDirectory = true,
            entries = entries
        )
        handles[handle.id] = handle
        return handle
    }

    /**
     * Get a handle by ID.
     */
    fun getHandle(handleId: String): OpenHandle? = handles[handleId]

    /**
     * Close and remove a handle.
     */
    fun closeHandle(handleId: String): Boolean {
        val handle = handles.remove(handleId) ?: return false
        try {
            handle.randomAccessFile?.close()
        } catch (e: IOException) {
            Log.d(TAG, "Error closing file handle: ${e.message}")
        }
        return true
    }

    /**
     * Close all open handles.
     */
    fun closeAllHandles() {
        handles.values.forEach { handle ->
            try {
                handle.randomAccessFile?.close()
            } catch (_: IOException) { }
        }
        handles.clear()
    }

    // ---- File Operations ----

    /**
     * Read data from an open file handle.
     *
     * @param handleId The file handle ID
     * @param offset The byte offset to read from
     * @param length The number of bytes to read
     * @return The data read, or null if EOF or error
     */
    fun readFile(handleId: String, offset: Long, length: Int): ByteArray? {
        val handle = handles[handleId]
        if (handle == null || handle.isDirectory || handle.randomAccessFile == null) {
            return null
        }

        try {
            val raf = handle.randomAccessFile!!
            raf.seek(offset)
            val buf = ByteArray(minOf(length, MAX_READ_SIZE))
            val bytesRead = raf.read(buf)
            if (bytesRead < 0) return null // EOF
            return if (bytesRead < buf.size) buf.copyOf(bytesRead) else buf
        } catch (e: IOException) {
            Log.w(TAG, "Read error for handle $handleId", e)
            return null
        }
    }

    /**
     * Write data to an open file handle.
     *
     * @param handleId The file handle ID
     * @param offset The byte offset to write at
     * @param data The data to write
     * @return Number of bytes written, or -1 on error
     */
    fun writeFile(handleId: String, offset: Long, data: ByteArray): Int {
        val handle = handles[handleId]
        if (handle == null || handle.isDirectory || handle.randomAccessFile == null) {
            return -1
        }

        try {
            val raf = handle.randomAccessFile!!
            raf.seek(offset)
            raf.write(data)
            return data.size
        } catch (e: IOException) {
            Log.w(TAG, "Write error for handle $handleId", e)
            return -1
        }
    }

    /**
     * Remove (delete) a file.
     *
     * @param path The resolved filesystem path
     * @return true if the file was deleted
     */
    fun removeFile(path: String): Boolean {
        val file = File(path)
        if (!file.exists() || !file.isFile) return false

        return try {
            file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete file: $path", e)
            false
        }
    }

    /**
     * Rename a file or directory.
     *
     * @param oldPath The resolved source path
     * @param newPath The resolved destination path
     * @return true if the rename succeeded
     */
    fun renameFile(oldPath: String, newPath: String): Boolean {
        val oldFile = File(oldPath)
        val newFile = File(newPath)

        if (!oldFile.exists()) return false

        // Ensure destination parent directory exists
        newFile.parentFile?.mkdirs()

        return try {
            oldFile.renameTo(newFile)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rename: $oldPath -> $newPath", e)
            false
        }
    }

    // ---- Directory Operations ----

    /**
     * Create a directory.
     *
     * @param path The resolved filesystem path
     * @return true if the directory was created
     */
    fun createDirectory(path: String): Boolean {
        val dir = File(path)
        if (dir.exists()) return false

        return try {
            dir.mkdirs()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create directory: $path", e)
            false
        }
    }

    /**
     * Remove a directory (must be empty).
     *
     * @param path The resolved filesystem path
     * @return true if the directory was removed
     */
    fun removeDirectory(path: String): Boolean {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return false

        return try {
            dir.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove directory: $path", e)
            false
        }
    }

    /**
     * Read the next batch of directory entries from an open directory handle.
     *
     * @param handleId The directory handle ID
     * @return List of FileEntry, or empty list if no more entries, or null on error
     */
    fun readDirectory(handleId: String): List<FileEntry>? {
        val handle = handles[handleId]
        if (handle == null || !handle.isDirectory || handle.entries == null) {
            return null
        }

        val entries = handle.entries!!
        if (handle.entryPosition >= entries.size) {
            return emptyList() // EOF
        }

        val batchEnd = minOf(handle.entryPosition + MAX_DIR_ENTRIES_PER_READ, entries.size)
        val batch = entries.subList(handle.entryPosition, batchEnd)

        val result = batch.map { file ->
            createFileEntry(file)
        }

        handle.entryPosition = batchEnd
        return result
    }

    // ---- File Attributes ----

    /**
     * Get file attributes for a given path.
     *
     * @param path The resolved filesystem path
     * @param followLinks Whether to follow symlinks (true for stat, false for lstat)
     * @return FileAttributes or null if the file doesn't exist
     */
    fun getFileAttributes(path: String, followLinks: Boolean = true): FileAttributes? {
        val file = File(path)
        if (!file.exists()) return null

        return createFileAttributes(file)
    }

    /**
     * Get file attributes from an open handle.
     *
     * @param handleId The file handle ID
     * @return FileAttributes or null if the handle is invalid
     */
    fun getFileAttributesByHandle(handleId: String): FileAttributes? {
        val handle = handles[handleId] ?: return null
        return createFileAttributes(handle.file)
    }

    /**
     * Set file attributes (best-effort on Android).
     *
     * Android's filesystem has limited support for chmod/chown from
     * application context. This method attempts to apply the requested
     * attributes where possible.
     *
     * @param path The resolved filesystem path
     * @param attrs The attributes to set
     * @return true if all requested operations succeeded
     */
    fun setFileAttributes(path: String, attrs: FileAttributes): Boolean {
        val file = File(path)
        if (!file.exists()) return false

        var success = true

        // Set modification time
        if ((attrs.flags and SSH_FILEXFER_ATTR_ACMODTIME) != 0) {
            val mtimeMs = attrs.mtime.toLong() * 1000
            if (!file.setLastModified(mtimeMs)) {
                Log.d(TAG, "Failed to set mtime for: $path")
                success = false
            }
        }

        // Set permissions (best-effort via chmod)
        if ((attrs.flags and SSH_FILEXFER_ATTR_PERMISSIONS) != 0) {
            try {
                val permStr = String.format("%03o", attrs.permissions and 0x1FF)
                val proc = Runtime.getRuntime().exec(
                    arrayOf("chmod", permStr, file.absolutePath)
                )
                val exitCode = proc.waitFor()
                if (exitCode != 0) {
                    Log.d(TAG, "chmod $permStr failed for: $path")
                    success = false
                }
            } catch (e: Exception) {
                Log.d(TAG, "chmod failed for: $path (${e.message})")
                success = false
            }
        }

        // UID/GID changes require root — not attempted

        return success
    }

    // ---- Attribute Helpers ----

    /**
     * Create a FileEntry from a Java File object.
     */
    fun createFileEntry(file: File): FileEntry {
        val permissions = computePermissions(file)
        val longName = formatLongName(file)

        return FileEntry(
            path = reverseMapPath(file.absolutePath),
            longName = longName,
            size = if (file.isFile) file.length() else 4096L,
            uid = 0,
            gid = 0,
            permissions = permissions,
            lastModified = file.lastModified()
        )
    }

    /**
     * Create FileAttributes from a Java File object.
     */
    private fun createFileAttributes(file: File): FileAttributes {
        return FileAttributes(
            size = if (file.isFile) file.length() else 4096L,
            uid = 0,
            gid = 0,
            permissions = computePermissions(file),
            atime = (file.lastModified() / 1000).toInt(),
            mtime = (file.lastModified() / 1000).toInt(),
            flags = SSH_FILEXFER_ATTR_SIZE or SSH_FILEXFER_ATTR_PERMISSIONS or SSH_FILEXFER_ATTR_ACMODTIME
        )
    }

    /**
     * Compute Unix-style permission bits for a file on Android.
     *
     * Since Android doesn't expose traditional Unix permission bits through
     * the Java File API, we infer them from canRead/canWrite/canExecute.
     */
    fun computePermissions(file: File): Int {
        var perms = 0

        // File type
        perms = if (file.isDirectory) {
            perms or S_IFDIR
        } else {
            perms or S_IFREG
        }

        // Owner permissions
        if (file.canRead()) perms = perms or S_IRUSR
        if (file.canWrite()) perms = perms or S_IWUSR
        if (file.canExecute()) perms = perms or S_IXUSR

        // Group/other permissions (mirrored from owner on Android)
        if (file.canRead()) {
            perms = perms or S_IRGRP or S_IROTH
        }
        if (file.canWrite()) {
            perms = perms or S_IWGRP  // Typically no group/other write
        }
        if (file.canExecute()) {
            perms = perms or S_IXGRP or S_IXOTH
        }

        return perms
    }

    /**
     * Format a long directory listing (ls -l style) for a file.
     * Used in SFTP NAME responses.
     *
     * Format: permissions links owner group size date name
     */
    fun formatLongName(file: File): String {
        val perms = formatPermissionString(file)
        val links = 1
        val owner = "termx"
        val group = "termx"
        val size = if (file.isFile) file.length() else 4096L
        val date = formatDate(file.lastModified())
        val name = file.name

        return String.format("%s %d %s %s %8d %s %s",
            perms, links, owner, group, size, date, name)
    }

    /**
     * Format file permissions as a Unix-style string (e.g., "drwxr-xr-x").
     */
    private fun formatPermissionString(file: File): String {
        val sb = StringBuilder()

        // File type
        sb.append(if (file.isDirectory) 'd' else '-')

        // Owner permissions
        sb.append(if (file.canRead()) 'r' else '-')
        sb.append(if (file.canWrite()) 'w' else '-')
        sb.append(if (file.canExecute()) 'x' else '-')

        // Group permissions
        sb.append(if (file.canRead()) 'r' else '-')
        sb.append(if (file.canWrite()) 'w' else '-')
        sb.append(if (file.canExecute()) 'x' else '-')

        // Other permissions
        sb.append(if (file.canRead()) 'r' else '-')
        sb.append(if (file.canWrite()) 'w' else '-')
        sb.append(if (file.canExecute()) 'x' else '-')

        return sb.toString()
    }

    /**
     * Format a timestamp for directory listing.
     */
    private fun formatDate(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val sixMonthsAgo = now - (180L * 24 * 60 * 60 * 1000)

        val format = if (timestamp > sixMonthsAgo) {
            SimpleDateFormat("MMM dd HH:mm", Locale.US)
        } else {
            SimpleDateFormat("MMM dd  yyyy", Locale.US)
        }

        return format.format(Date(timestamp))
    }

    // ---- Disk Space ----

    /**
     * Get disk space information for a path.
     * Used for the statvfs@openssh.com extended SFTP operation.
     *
     * @param path The resolved filesystem path
     * @return StatVfs data or null if unavailable
     */
    fun getStatVfs(path: String): StatVfsData? {
        val file = File(path)
        if (!file.exists()) return null

        return try {
            val totalSpace = file.totalSpace
            val freeSpace = file.freeSpace
            val availableSpace = file.usableSpace

            StatVfsData(
                bsize = 4096,
                frsize = 4096,
                blocks = totalSpace / 4096L,
                bfree = freeSpace / 4096L,
                bavail = availableSpace / 4096L,
                files = 1000000,
                ffree = 900000,
                fNameLen = 255
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * StatVfs data for disk space reporting.
     */
    data class StatVfsData(
        val bsize: Long,      // File system block size
        val frsize: Long,     // Fundamental file system block size
        val blocks: Long,     // Total data blocks in file system
        val bfree: Long,      // Free blocks in file system
        val bavail: Long,     // Free blocks available to non-privileged users
        val files: Long,      // Total file nodes
        val ffree: Long,      // Free file nodes
        val fNameLen: Long    // Maximum length of file name
    )

    // ---- SFTP Packet Helpers ----

    /**
     * Encode a file attributes structure for SFTP responses.
     *
     * Wire format:
     *   uint32   flags
     *   uint64   size              (if SSH_FILEXFER_ATTR_SIZE)
     *   uint32   uid               (if SSH_FILEXFER_ATTR_UIDGID)
     *   uint32   gid               (if SSH_FILEXFER_ATTR_UIDGID)
     *   uint32   permissions       (if SSH_FILEXFER_ATTR_PERMISSIONS)
     *   uint32   atime             (if SSH_FILEXFER_ATTR_ACMODTIME)
     *   uint32   mtime             (if SSH_FILEXFER_ATTR_ACMODTIME)
     *   string   extended_type     (if SSH_FILEXFER_ATTR_EXTENDED)
     *   string   extended_data     (if SSH_FILEXFER_ATTR_EXTENDED)
     */
    fun encodeAttrs(attrs: FileAttributes): ByteArray {
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)

        out.writeInt(attrs.flags)

        if ((attrs.flags and SSH_FILEXFER_ATTR_SIZE) != 0) {
            out.writeLong(attrs.size)
        }

        if ((attrs.flags and SSH_FILEXFER_ATTR_UIDGID) != 0) {
            out.writeInt(attrs.uid)
            out.writeInt(attrs.gid)
        }

        if ((attrs.flags and SSH_FILEXFER_ATTR_PERMISSIONS) != 0) {
            out.writeInt(attrs.permissions)
        }

        if ((attrs.flags and SSH_FILEXFER_ATTR_ACMODTIME) != 0) {
            out.writeInt(attrs.atime)
            out.writeInt(attrs.mtime)
        }

        if ((attrs.flags and SSH_FILEXFER_ATTR_EXTENDED) != 0) {
            for ((type, data) in attrs.extended) {
                out.write(encodeSshString(type))
                out.write(encodeSshString(data))
            }
        }

        return buf.toByteArray()
    }

    /**
     * Parse file attributes from an SFTP request payload.
     *
     * @param input DataInputStream positioned at the start of the attrs
     * @return FileAttributes parsed from the stream
     */
    fun parseAttrs(input: DataInputStream): FileAttributes {
        val flags = input.readInt()
        var size = 0L
        var uid = 0
        var gid = 0
        var permissions = 0
        var atime = 0
        var mtime = 0
        val extended = mutableMapOf<String, String>()

        if ((flags and SSH_FILEXFER_ATTR_SIZE) != 0) {
            size = input.readLong()
        }

        if ((flags and SSH_FILEXFER_ATTR_UIDGID) != 0) {
            uid = input.readInt()
            gid = input.readInt()
        }

        if ((flags and SSH_FILEXFER_ATTR_PERMISSIONS) != 0) {
            permissions = input.readInt()
        }

        if ((flags and SSH_FILEXFER_ATTR_ACMODTIME) != 0) {
            atime = input.readInt()
            mtime = input.readInt()
        }

        if ((flags and SSH_FILEXFER_ATTR_EXTENDED) != 0) {
            val count = input.readInt()
            repeat(count) {
                val type = readSshString(input)
                val data = readSshString(input)
                extended[type] = data
            }
        }

        return FileAttributes(
            size = size,
            uid = uid,
            gid = gid,
            permissions = permissions,
            atime = atime,
            mtime = mtime,
            flags = flags,
            extended = extended
        )
    }

    // ---- Encoding Helpers ----

    /**
     * Encode a string as SSH wire format (uint32 length + UTF-8 data).
     */
    private fun encodeSshString(s: String): ByteArray {
        val data = s.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        out.writeInt(data.size)
        out.write(data)
        return buf.toByteArray()
    }

    /**
     * Read an SSH string from a DataInputStream.
     */
    private fun readSshString(input: DataInputStream): String {
        val len = input.readInt()
        if (len < 0 || len > MAX_PATH_LENGTH) {
            throw IOException("Invalid string length: $len")
        }
        val data = ByteArray(len)
        input.readFully(data)
        return String(data, StandardCharsets.UTF_8)
    }

    // ---- SCP Support ----

    /**
     * Handle an SCP (Secure Copy) command.
     * SCP is an older file transfer protocol that runs over the SSH exec channel.
     *
     * SCP protocol flow:
     *   1. Client sends: scp -t <path>   (to destination - upload)
     *      or: scp -f <path>             (from destination - download)
     *   2. Server and client exchange file data using SCP protocol messages
     *
     * This implementation provides basic SCP support as an alternative to SFTP.
     *
     * @param command The SCP command string from the client
     * @param output OutputStream to write SCP protocol messages
     * @param input InputStream to read SCP protocol messages
     */
    fun handleScpCommand(command: String, output: OutputStream, input: InputStream) {
        val parts = command.trim().split("\\s+".toRegex())

        if (parts.size < 3) {
            output.write("0\n".toByteArray())
            output.write("Invalid SCP command\n".toByteArray())
            return
        }

        val scpFlags = parts[1]
        val path = parts.subList(2, parts.size).first { !it.startsWith("-") }
        val resolvedPath = resolvePath(path)

        when {
            scpFlags.contains("t") -> handleScpUpload(resolvedPath, output, input)
            scpFlags.contains("f") -> handleScpDownload(resolvedPath, output, input)
            else -> {
                output.write("1\nUnsupported SCP flags\n".toByteArray())
            }
        }
    }

    /**
     * Handle an SCP upload (client -> server).
     */
    private fun handleScpUpload(path: String, output: OutputStream, input: InputStream) {
        try {
            // Acknowledge readiness
            output.write(0)

            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
            val writer = output

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line!!

                when {
                    // C0755 size filename — create file
                    currentLine.startsWith("C") -> {
                        val fileParts = currentLine.substring(1).split("\\s+".toRegex(), 3)
                        if (fileParts.size < 3) {
                            writer.write(1)
                            continue
                        }

                        val size = fileParts[0].toLongOrNull() ?: 0L
                        val fileName = fileParts[2]
                        val filePath = "$path/$fileName"

                        writer.write(0) // Acknowledge

                        // Read file data
                        val file = File(filePath)
                        file.parentFile?.mkdirs()
                        val fos = FileOutputStream(file)

                        var remaining = size
                        val buffer = ByteArray(8192)
                        while (remaining > 0) {
                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val bytesRead = input.read(buffer, 0, toRead)
                            if (bytesRead < 0) break
                            fos.write(buffer, 0, bytesRead)
                            remaining -= bytesRead
                        }
                        fos.close()

                        // Read trailing newline
                        input.read()

                        writer.write(0) // Acknowledge completion
                    }
                    // D0755 0 dirname — create directory
                    currentLine.startsWith("D") -> {
                        val dirParts = currentLine.substring(1).split("\\s+".toRegex(), 3)
                        if (dirParts.size >= 3) {
                            val dirName = dirParts[2]
                            val dirPath = "$path/$dirName"
                            File(dirPath).mkdirs()
                        }
                        writer.write(0)
                    }
                    // E — end of directory
                    currentLine == "E" -> {
                        writer.write(0)
                        break
                    }
                    // T — timestamp (acknowledge and skip)
                    currentLine.startsWith("T") -> {
                        writer.write(0)
                    }
                    else -> {
                        writer.write(1)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SCP upload error", e)
        }
    }

    /**
     * Handle an SCP download (server -> client).
     */
    private fun handleScpDownload(path: String, output: OutputStream, input: InputStream) {
        try {
            // Wait for client acknowledgment (0 byte)
            val ack = input.read()
            if (ack != 0) return

            val file = File(path)
            if (!file.exists()) {
                output.write("1\nNo such file or directory\n".toByteArray())
                return
            }

            if (file.isDirectory) {
                sendScpDirectory(file, output, input)
            } else {
                sendScpFile(file, output, input)
            }
        } catch (e: Exception) {
            Log.w(TAG, "SCP download error", e)
        }
    }

    /**
     * Send a file via SCP protocol.
     */
    private fun sendScpFile(file: File, output: OutputStream, input: InputStream) {
        // Send timestamp
        val mtime = file.lastModified() / 1000
        output.write("T${mtime} 0 ${mtime} 0\n".toByteArray())
        if (input.read() != 0) return

        // Send file header: C<perms> <size> <filename>
        val perms = computePermissions(file) and 0x1FF
        val header = String.format("C%04o %d %s\n", perms, file.length(), file.name)
        output.write(header.toByteArray())
        if (input.read() != 0) return

        // Send file data
        val fis = FileInputStream(file)
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (fis.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
        fis.close()

        // Send trailing byte and wait for acknowledgment
        output.write(0)
        input.read()
    }

    /**
     * Send a directory recursively via SCP protocol.
     */
    private fun sendScpDirectory(dir: File, output: OutputStream, input: InputStream) {
        // Send timestamp
        val mtime = dir.lastModified() / 1000
        output.write("T${mtime} 0 ${mtime} 0\n".toByteArray())
        if (input.read() != 0) return

        // Send directory header
        val perms = computePermissions(dir) and 0x1FF
        val header = String.format("D%04o 0 %s\n", perms, dir.name)
        output.write(header.toByteArray())
        if (input.read() != 0) return

        // Send contents
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                sendScpDirectory(file, output, input)
            } else {
                sendScpFile(file, output, input)
            }
        }

        // Send end of directory
        output.write("E\n".toByteArray())
        if (input.read() != 0) return
    }

    // ---- Cleanup ----

    /**
     * Clean up stale handles that have been open too long.
     */
    fun cleanupStaleHandles(maxAgeMs: Long = 3600000) { // Default: 1 hour
        val now = System.currentTimeMillis()
        val staleIds = handles.entries
            .filter { now - it.value.createdAt > maxAgeMs }
            .map { it.key }

        staleIds.forEach { id ->
            Log.d(TAG, "Closing stale handle: $id")
            closeHandle(id)
        }

        if (staleIds.isNotEmpty()) {
            Log.i(TAG, "Cleaned up ${staleIds.size} stale SFTP handle(s)")
        }
    }

    /**
     * Get a summary of the SFTP subsystem state.
     */
    fun getSummary(): String {
        return buildString {
            appendLine("TermX SFTP Subsystem")
            appendLine("  Protocol version: $SFTP_VERSION")
            appendLine("  Open handles: ${handles.size}")
            appendLine("  Virtual root: $virtualRoot")
            appendLine("  External access: ${if (allowExternalAccess) "allowed" else "restricted"}")
            appendLine("  Allowed roots:")
            allowedRoots.forEach { appendLine("    - $it") }

            if (handles.isNotEmpty()) {
                appendLine("  Active handles:")
                handles.values.forEach { h ->
                    val type = if (h.isDirectory) "DIR" else "FILE"
                    appendLine("    [$type] ${h.path} (age=${(System.currentTimeMillis() - h.createdAt) / 1000}s)")
                }
            }
        }
    }
}
