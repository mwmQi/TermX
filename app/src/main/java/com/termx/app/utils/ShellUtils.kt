package com.termx.app.utils

import android.util.Log
import java.io.*

/**
 * Utility class for shell-related operations.
 */
object ShellUtils {

    private const val TAG = "ShellUtils"

    /**
     * Execute a shell command and return the output.
     */
    fun execute(command: String): ShellResult {
        return execute(command, "/")
    }

    /**
     * Execute a shell command in a given directory.
     */
    fun execute(command: String, cwd: String): ShellResult {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", command),
                null,
                File(cwd)
            )

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            ShellResult(stdout, stderr, exitCode)
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed: $command", e)
            ShellResult("", e.message ?: "Unknown error", -1)
        }
    }

    data class ShellResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    ) {
        val isSuccess: Boolean get() = exitCode == 0
        val output: String get() = if (stdout.isNotBlank()) stdout else stderr
    }

    /**
     * Check if a command exists in PATH.
     */
    fun commandExists(command: String): Boolean {
        val result = execute("which $command")
        return result.isSuccess && result.stdout.isNotBlank()
    }

    /**
     * Get the system PATH.
     */
    fun getPath(): String {
        return System.getenv("PATH") ?: "/system/bin:/system/xbin"
    }

    /**
     * Get available shells on the device.
     */
    fun getAvailableShells(): List<String> {
        val shells = mutableListOf("/system/bin/sh")
        for (shell in listOf("/system/bin/bash", "/system/xbin/bash", "/data/data/com.termux/files/usr/bin/bash")) {
            if (File(shell).exists()) {
                shells.add(shell)
            }
        }
        return shells
    }

    /**
     * Get system information.
     */
    fun getSystemInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("=== TermX System Info ===")
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("Kernel: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
        sb.appendLine("Shell: ${getAvailableShells().joinToString(", ")}")
        sb.appendLine("PATH: ${getPath()}")
        return sb.toString()
    }

    /**
     * List directory contents.
     */
    fun listDirectory(path: String): List<FileEntry> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        return dir.listFiles()?.map { file ->
            FileEntry(
                name = file.name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0,
                lastModified = file.lastModified(),
                isHidden = file.name.startsWith("."),
                isReadable = file.canRead(),
                isWritable = file.canWrite(),
                isExecutable = file.canExecute()
            )
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }

    data class FileEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val isHidden: Boolean,
        val isReadable: Boolean,
        val isWritable: Boolean,
        val isExecutable: Boolean
    )

    /**
     * Read file content as text.
     */
    fun readFile(path: String): String {
        return try {
            File(path).readText()
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }
    }

    /**
     * Write text to a file.
     */
    fun writeFile(path: String, content: String): Boolean {
        return try {
            File(path).writeText(content)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write file: $path", e)
            false
        }
    }
}
