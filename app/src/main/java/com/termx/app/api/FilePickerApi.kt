package com.termx.app.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

object FilePickerApi {
    private const val TAG = "FilePickerApi"
    
    fun openFilePicker(context: Context, mimeType: String = "*/*"): String {
        return try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "File picker opened for MIME: $mimeType"
        } catch (e: Exception) {
            Log.e(TAG, "File picker failed", e)
            "Failed to open file picker: ${e.message}"
        }
    }
    
    fun openSaveFilePicker(context: Context, filename: String, mimeType: String = "*/*"): String {
        return try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
                putExtra(Intent.EXTRA_TITLE, filename)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Save file picker opened: $filename"
        } catch (e: Exception) {
            Log.e(TAG, "Save file picker failed", e)
            "Failed to open save picker: ${e.message}"
        }
    }
    
    fun openDirectoryPicker(context: Context): String {
        return try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Directory picker opened"
        } catch (e: Exception) {
            Log.e(TAG, "Directory picker failed", e)
            "Failed to open directory picker: ${e.message}"
        }
    }
}
