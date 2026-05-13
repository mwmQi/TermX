package com.termx.app.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * File Picker API for TermX. File/directory picker and save via Storage Access Framework.
 *
 * Usage: am broadcast -a com.termx.app.api.FILE_PICK --es mime "image/*"
 *        am broadcast -a com.termx.app.api.FILE_PICK_MULTI --es mime "application/pdf"
 *        am broadcast -a com.termx.app.api.FILE_PICK_DIR
 *        am broadcast -a com.termx.app.api.FILE_SAVE --es filename "output.txt" --es mime "text/plain"
 * Note: Pickers require Activity context for result delivery.
 */
object FilePickerApi {

    private const val TAG = "FilePickerApi"
    private const val RESULT_FILE = "file_picker_result.txt"
    const val REQUEST_PICK_FILE = 6001; const val REQUEST_PICK_FILES = 6002
    const val REQUEST_PICK_DIR = 6003; const val REQUEST_SAVE_FILE = 6004

    data class PickedFile(val uri: String, val name: String, val mimeType: String, val size: Long) {
        fun toFormattedString() = "URI: $uri\nName: $name\nMIME: $mimeType\nSize: $size bytes"
    }

    /** Open file picker for a specific MIME type. */
    fun openFilePicker(context: Context, mimeType: String = "*/*"): String = try {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context.packageManager.queryIntentActivities(intent, 0).isEmpty())
            return "Error: No file picker app for MIME: $mimeType"
        saveRequestInfo(context, "pick", mimeType)
        if (context is android.app.Activity) { context.startActivityForResult(intent, REQUEST_PICK_FILE); "File picker opened" }
        else { context.startActivity(Intent.createChooser(intent, "Select File").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "File picker launched" }
    } catch (e: Exception) { Log.e(TAG, "Pick failed", e); "Error: ${e.message}" }

    /** Open multi-file picker. */
    fun openMultiFilePicker(context: Context, mimeType: String = "*/*"): String = try {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mimeType
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        saveRequestInfo(context, "pick_multi", mimeType)
        if (context is android.app.Activity) { context.startActivityForResult(intent, REQUEST_PICK_FILES); "Multi-picker opened" }
        else { context.startActivity(Intent.createChooser(intent, "Select Files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Multi-picker launched" }
    } catch (e: Exception) { "Error: ${e.message}" }

    /** Open directory picker. */
    fun openDirectoryPicker(context: Context): String = try {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        saveRequestInfo(context, "pick_dir", "directory")
        if (context is android.app.Activity) { context.startActivityForResult(intent, REQUEST_PICK_DIR); "Directory picker opened" }
        else { context.startActivity(Intent.createChooser(intent, "Select Dir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Directory picker launched" }
    } catch (e: Exception) { "Error: ${e.message}" }

    /** Open save file dialog. */
    fun openSaveFilePicker(context: Context, filename: String = "output.txt", mimeType: String = "text/plain"): String = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return "Error: Requires API 19+"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mimeType; putExtra(Intent.EXTRA_TITLE, filename)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        saveRequestInfo(context, "save", mimeType)
        if (context is android.app.Activity) { context.startActivityForResult(intent, REQUEST_SAVE_FILE); "Save picker opened: $filename" }
        else { context.startActivity(Intent.createChooser(intent, "Save").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Save picker launched: $filename" }
    } catch (e: Exception) { "Error: ${e.message}" }

    /** Handle picked file URI result from onActivityResult. */
    fun handlePickResult(context: Context, uri: Uri): String = try {
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { cur ->
            val i = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cur.moveToFirst() && i >= 0) cur.getString(i) else "unknown"
        } ?: "unknown"
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val picked = PickedFile(uri.toString(), name, mime, getFileSize(context, uri))
        File(context.cacheDir, RESULT_FILE).writeText(picked.toFormattedString())
        Log.i(TAG, "Picked: $name"); picked.toFormattedString()
    } catch (e: Exception) { "Error: ${e.message}" }

    /** Copy content URI to a local file. */
    fun copyUriToFile(context: Context, uri: Uri, destPath: String): String = try {
        val dest = File(destPath); dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { inp -> FileOutputStream(dest).use { out -> inp.copyTo(out) } }
        "Copied to $destPath (${dest.length()} bytes)"
    } catch (e: Exception) { "Error: ${e.message}" }

    /** Persist URI permission for access after reboot. */
    fun persistUriPermission(context: Context, uri: Uri, writable: Boolean = true) {
        try { context.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        } catch (e: Exception) { Log.w(TAG, "Persist failed", e) }
    }

    private fun saveRequestInfo(ctx: Context, type: String, mime: String) =
        try { File(ctx.cacheDir, "file_picker_request.txt").writeText("type=$type\nmime=$mime\nts=${System.currentTimeMillis()}") } catch (_: Exception) {}

    private fun getFileSize(ctx: Context, uri: Uri): Long = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (c.moveToFirst() && i >= 0) c.getLong(i) else -1L
        } ?: -1L
    } catch (_: Exception) { -1L }
}
