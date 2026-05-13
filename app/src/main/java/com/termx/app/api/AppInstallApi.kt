package com.termx.app.api

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File

/**
 * App Install/Management API for TermX. Install, uninstall, list, info, launch apps.
 *
 * Usage: am broadcast -a com.termx.app.api.APP_INSTALL --es path "/path/app.apk"
 *        am broadcast -a com.termx.app.api.APP_UNINSTALL --es package "com.example"
 *        am broadcast -a com.termx.app.api.APP_LIST --es type "user"
 *        am broadcast -a com.termx.app.api.APP_INFO --es package "com.example"
 *        am broadcast -a com.termx.app.api.APP_LAUNCH --es package "com.example"
 * Requires: REQUEST_INSTALL_PACKAGES, QUERY_ALL_PACKAGES (API 30+)
 */
object AppInstallApi {

    private const val TAG = "AppInstallApi"

    /** Install APK from file path. */
    fun installApk(context: Context, path: String): String { return try {
        val apk = File(path)
        if (!apk.exists()) return "Error: APK not found: $path"
        if (!apk.name.endsWith(".apk")) return "Error: Not an APK file"
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        else Uri.fromFile(apk)
        context.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        Log.i(TAG, "Install: ${apk.name}"); "Install initiated: ${apk.name} (${apk.length()}B)"
    } catch (e: SecurityException) { "Error: REQUEST_INSTALL_PACKAGES permission required" }
    catch (e: Exception) { Log.e(TAG, "Install failed", e); "Error: ${e.message}" }
    }

    /** Uninstall app by package name. */
    fun uninstallApp(context: Context, packageName: String): String { return try {
        if (packageName.isBlank()) return "Error: Package name required"
        try { context.packageManager.getPackageInfo(packageName, 0) }
        catch (_: PackageManager.NameNotFoundException) { return "Error: Package not installed: $packageName" }
        context.startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$packageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        "Uninstall initiated: $packageName"
    } catch (e: Exception) { "Error: ${e.message}" }
    }

    /** Check if app is installed. */
    fun isAppInstalled(context: Context, packageName: String): String = try {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        "Installed: $packageName v${info.versionName}"
    } catch (_: PackageManager.NameNotFoundException) { "Not installed: $packageName" }
    catch (e: Exception) { "Error: ${e.message}" }

    /** List installed apps. type: "all", "system", "user", "enabled", "disabled". */
    fun listApps(context: Context, type: String = "all", limit: Int = 100): String = try {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) PackageManager.MATCH_UNINSTALLED_PACKAGES
            else @Suppress("DEPRECATION") PackageManager.GET_UNINSTALLED_PACKAGES
        val pkgs = pm.getInstalledPackages(flags)
        val filtered = when (type) {
            "system" -> pkgs.filter { it.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0 }
            "user" -> pkgs.filter { it.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) == 0 }
            "enabled" -> pkgs.filter { it.applicationInfo?.enabled == true }
            "disabled" -> pkgs.filter { it.applicationInfo?.enabled == false }
            else -> pkgs
        }
        val lines = filtered.take(limit).map { p ->
            val name = p.applicationInfo?.loadLabel(pm) ?: p.packageName
            val sys = if (p.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0) "system" else "user"
            "$name | ${p.packageName} | v${p.versionName} | $sys"
        }
        if (lines.isEmpty()) "No apps for filter: $type"
        else "=== Apps (${filtered.size} $type, showing ${lines.size}) ===\n" + lines.joinToString("\n") { "  $it" }
    } catch (e: Exception) { Log.e(TAG, "List failed", e); "Error: ${e.message}" }

    /** Get detailed app info. */
    fun getAppInfo(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        val pi = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val ai = pi.applicationInfo
        val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) ai?.minSdkVersion ?: 0 else 0
        val perms = pi.requestedPermissions?.joinToString(", ") ?: "none"
        val sys = if (ai?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0) "yes" else "no"
        buildString {
            appendLine("=== ${ai?.loadLabel(pm) ?: packageName} ===")
            appendLine("Package:     $packageName")
            appendLine("Version:     ${pi.versionName} ($vCode)")
            appendLine("Target SDK:  ${ai?.targetSdkVersion}"); appendLine("Min SDK:     $minSdk")
            appendLine("System:      $sys"); appendLine("Enabled:     ${ai?.enabled}")
            appendLine("APK:         ${ai?.sourceDir}"); appendLine("UID:         ${ai?.uid}")
            appendLine("Permissions: $perms")
        }
    } catch (_: PackageManager.NameNotFoundException) { "Error: Package not found: $packageName" }
    catch (e: Exception) { "Error: ${e.message}" }

    /** Launch app by package name. */
    fun launchApp(context: Context, packageName: String): String { return try {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "Error: No launchable activity for $packageName"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent); Log.i(TAG, "Launched: $packageName"); "Launched: $packageName"
    } catch (_: PackageManager.NameNotFoundException) { "Error: Package not found: $packageName" }
    catch (e: Exception) { "Error: ${e.message}" }
    }
}
