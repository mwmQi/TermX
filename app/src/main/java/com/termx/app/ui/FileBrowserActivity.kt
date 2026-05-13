package com.termx.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.termx.app.utils.ShellUtils
import java.io.File

/**
 * Simple file browser for navigating the Android filesystem.
 */
class FileBrowserActivity : AppCompatActivity() {

    private lateinit var currentPath: String
    private lateinit var pathText: TextView
    private lateinit var fileList: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var files: List<ShellUtils.FileEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(com.termx.app.R.string.file_browser_title)

        currentPath = intent.getStringExtra("path") ?: "/"

        setupUI()
        navigateTo(currentPath)
    }

    private fun setupUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        pathText = TextView(this).apply {
            textSize = 14f
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xFF2A2B3C.toInt())
            setTextColor(0xFFD4BE98.toInt())
        }
        layout.addView(pathText)

        fileList = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_NONE
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                if (position < files.size) {
                    val entry = files[position]
                    if (entry.isDirectory) {
                        navigateTo(entry.path)
                    } else {
                        // Open file in terminal
                        val resultIntent = Intent().apply {
                            putExtra("file_path", entry.path)
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    }
                }
            }
        }
        layout.addView(fileList)

        setContentView(layout)
    }

    private fun navigateTo(path: String) {
        currentPath = path
        pathText.text = path

        files = ShellUtils.listDirectory(path)

        // Add parent directory
        val displayNames = mutableListOf<String>()
        if (path != "/") {
            displayNames.add("../ (parent)")
        }

        for (file in files) {
            val prefix = if (file.isDirectory) "/" else ""
            val suffix = if (file.isDirectory) "/" else ""
            val hidden = if (file.isHidden) "." else ""
            val size = if (file.isFile) " (${formatSize(file.size)})" else ""
            displayNames.add("$hidden${file.name}$suffix$size")
        }

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayNames)
        fileList.adapter = adapter
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (currentPath != "/") {
            navigateTo(File(currentPath).parent ?: "/")
            return true
        }
        finish()
        return true
    }

    override fun onBackPressed() {
        if (currentPath != "/") {
            navigateTo(File(currentPath).parent ?: "/")
        } else {
            super.onBackPressed()
        }
    }
}
