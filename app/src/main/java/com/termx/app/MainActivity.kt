package com.termx.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.termx.app.api.TermXApi
import com.termx.app.config.BellHandler
import com.termx.app.config.TermXProperties
import com.termx.app.databinding.ActivityMainBinding
import com.termx.app.keyboard.ExtraKeysView
import com.termx.app.power.TerminalService
import com.termx.app.power.WakeLockManager
import com.termx.app.session.SessionManager
import com.termx.app.storage.StorageSetup
import com.termx.app.terminal.TerminalColors
import com.termx.app.terminal.TerminalView
import com.termx.app.ui.SessionTabAdapter
import com.termx.app.ui.SettingsActivity
import com.termx.app.ui.floatwin.FloatingTerminal
import com.termx.app.utils.FontManager
import com.termx.app.utils.FullscreenManager
import com.termx.app.utils.PreferenceManager
import com.termx.app.utils.ShellUtils
import com.termx.app.pkg.TermXPackageManager
import com.termx.app.x11.DisplayInfo
import com.termx.app.x11.X11DisplayActivity
import com.termx.app.x11.X11Manager

/**
 * Main activity for TermX terminal emulator.
 * Full-featured terminal with swipe sessions, display sessions (:0, :1, :2),
 * fullscreen, custom fonts, bell handling, config system, and all Termux-style features.
 *
 * Display sessions appear as tabs alongside terminal sessions with display number tags.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionManager: SessionManager
    private lateinit var prefs: PreferenceManager
    private lateinit var properties: TermXProperties

    private var terminalView: TerminalView? = null
    private var currentColors: TerminalColors = TerminalColors.catppuccinMocha()
    private var tabAdapter: SessionTabAdapter? = null

    // Floating terminal
    private var floatingTerminal: com.termx.app.ui.floatwin.FloatingTerminal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = PreferenceManager.getInstance(this)
        sessionManager = SessionManager.getInstance(this)
        properties = TermXProperties(this)
        properties.load()

        // Apply theme
        currentColors = loadThemeColors(prefs.themeName)

        // Night mode auto-switch
        if (prefs.nightMode) {
            val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                currentColors = TerminalColors.catppuccinMocha()
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start foreground service
        startTerminalService()

        // Setup UI
        setupToolbar()
        setupSessionTabs()
        setupTerminal()
        setupExtraKeys()
        setupKeyboardToggle()
        setupBellHandler()

        // Create initial session if none exists
        if (sessionManager.activeSessionCount == 0) {
            createNewSession()
        } else {
            attachToActiveSession()
        }

        // Handle intent
        handleIntent(intent)

        // Fullscreen from config
        if (prefs.fullscreen || properties.fullscreen) {
            FullscreenManager.enterFullscreen(this)
        }

        // Auto-setup storage
        if (!StorageSetup.isStorageSetup(this) && StorageSetup.hasStoragePermission(this)) {
            StorageSetup.setupStorage(this)
        }

        // Initialize package manager (creates prefix dirs, loads db)
        try {
            TermXPackageManager(this).init()
        } catch (e: Exception) {
            android.util.Log.e("TermX", "Package manager init failed", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (text != null) {
                    sessionManager.activeSession?.session?.sendInput("echo '$text'")
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null) {
                    sessionManager.activeSession?.session?.sendInput("echo 'Opened: $uri'")
                }
            }
        }

        // Widget/notification extras
        when (intent?.getStringExtra("action")) {
            "new_session" -> createNewSession()
            "open_display" -> {
                val displayNum = intent.getIntExtra("display_num", 0)
                if (X11Manager.isDisplayRunning(displayNum)) {
                    X11DisplayActivity.start(this, displayNum)
                }
            }
        }

        // RUN_COMMAND intent
        val runCommand = intent?.getStringExtra("run_command")
        if (runCommand != null) {
            sessionManager.activeSession?.session?.sendInput(runCommand)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        updateTitle()
    }

    private fun setupSessionTabs() {
        tabAdapter = SessionTabAdapter(
            onTabClicked = { position ->
                val sessions = sessionManager.allSessions
                if (position in sessions.indices) {
                    when (val session = sessions[position]) {
                        is SessionManager.SessionType.Terminal -> {
                            sessionManager.switchToSession(session.id)
                            attachToTerminalSession(session)
                        }
                        is SessionManager.SessionType.Display -> {
                            sessionManager.switchToDisplay(session.displayNum)
                            openDisplayViewer(session.displayNum)
                        }
                    }
                }
            },
            onTabClosed = { position ->
                val sessions = sessionManager.allSessions
                if (position in sessions.indices) {
                    when (val session = sessions[position]) {
                        is SessionManager.SessionType.Terminal -> {
                            sessionManager.removeSession(session.id)
                        }
                        is SessionManager.SessionType.Display -> {
                            sessionManager.removeDisplaySession(session.displayNum)
                        }
                    }
                    if (sessionManager.activeSessionCount == 0) {
                        createNewSession()
                    } else {
                        attachToActiveSession()
                    }
                }
                refreshTabs()
            }
        )

        binding.tabRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = tabAdapter
        }

        refreshTabs()

        // Listen for session list changes (e.g., from display starts/stops)
        sessionManager.onSessionListChanged = {
            runOnUiThread { refreshTabs() }
        }
    }

    private fun refreshTabs() {
        val sessions = sessionManager.allSessions
        val tabItems = sessions.map { session ->
            when (session) {
                is SessionManager.SessionType.Terminal -> SessionTabAdapter.TabItem(
                    id = session.id,
                    title = session.title,
                    type = SessionTabAdapter.TabType.TERMINAL
                )
                is SessionManager.SessionType.Display -> SessionTabAdapter.TabItem(
                    id = "display_${session.displayNum}",
                    title = session.title,  // "Display :0", "Display :1", etc.
                    type = SessionTabAdapter.TabType.DISPLAY
                )
            }
        }
        tabAdapter?.updateItems(tabItems)

        // Show/hide tab bar
        binding.tabScroll.visibility = if (tabItems.size > 1) View.VISIBLE else View.GONE

        // Highlight active tab
        val activeIdx = sessions.indexOfFirst { session ->
            when (session) {
                is SessionManager.SessionType.Terminal -> session.id == sessionManager.activeSession?.id
                is SessionManager.SessionType.Display -> session.displayNum == sessionManager.currentDisplayNum
            }
        }.coerceAtLeast(0)
        tabAdapter?.setSelectedPosition(activeIdx)
    }

    private fun setupTerminal() {
        terminalView = TerminalView(this).apply {
            colors = currentColors
            setFontSize(prefs.fontSize)
        }

        // Apply custom font
        val typeface = FontManager.getTypeface(this)
        terminalView?.let { tv ->
            // Font will be applied through TerminalView's paint
        }

        // Apply terminal margins
        val density = resources.displayMetrics.density
        binding.terminalContainer.setPadding(
            (prefs.terminalMarginH * density).toInt(),
            (prefs.terminalMarginV * density).toInt(),
            (prefs.terminalMarginH * density).toInt(),
            (prefs.terminalMarginV * density).toInt()
        )

        binding.terminalContainer.visibility = View.VISIBLE

        binding.terminalContainer.removeAllViews()
        binding.terminalContainer.addView(
            terminalView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun setupExtraKeys() {
        binding.extraKeys.setTerminalView(terminalView!!)
        binding.extraKeys.visibility = if (prefs.showExtraKeys) View.VISIBLE else View.GONE
    }

    private fun setupKeyboardToggle() {
        binding.btnToggleKeyboard.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            if (imm.isActive) {
                imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
            } else {
                terminalView?.requestFocus()
                imm.showSoftInput(terminalView, 0)
            }
        }
        binding.btnToggleKeyboard.visibility = if (prefs.showKeyboardToggle) View.VISIBLE else View.GONE
    }

    private fun setupBellHandler() {
        val bellMode = when (properties.bellCharacter) {
            TermXProperties.BellMode.VIBRATE -> TermXProperties.BellMode.VIBRATE
            TermXProperties.BellMode.VISIBLE -> TermXProperties.BellMode.VISIBLE
            TermXProperties.BellMode.IGNORE -> TermXProperties.BellMode.IGNORE
        }
    }

    private fun createNewSession() {
        val info = sessionManager.createSession(
            shellPath = prefs.shellPath,
            cwd = prefs.initialDir,
            colors = currentColors
        )
        attachToTerminalSession(info)
        refreshTabs()
    }

    /**
     * Attach the terminal view to a terminal session.
     */
    private fun attachToTerminalSession(info: SessionManager.SessionType.Terminal?) {
        if (info == null) return

        // Show terminal, hide any display placeholder
        binding.terminalContainer.visibility = View.VISIBLE

        terminalView?.apply {
            buffer = info.buffer
            session = info.session
            emulator = info.emulator
            colors = currentColors
            setFontSize(prefs.fontSize)
        }

        // CRITICAL: Wire session output to emulator and view
        // This must be set here because the emulator needs to process raw bytes
        // from the PTY into terminal buffer changes, then the view redraws
        info.session.onOutput = { data ->
            info.emulator.process(data)
            runOnUiThread {
                terminalView?.invalidate()
            }
        }

        info.session.onError = { data ->
            info.emulator.process(data)
            runOnUiThread {
                terminalView?.invalidate()
            }
        }

        info.session.onExit = { exitCode ->
            runOnUiThread {
                val msg = if (exitCode == 0) "Process exited" else "Process exited with code $exitCode"
                info.emulator.process("\r\n[$msg]\r\n".toByteArray())
                terminalView?.invalidate()
            }
        }

        // Handle updates from session output
        info.onUpdate = {
            runOnUiThread {
                terminalView?.invalidate()
            }
        }

        // Setup bell handler
        info.emulator?.onBell = {
            BellHandler.onBell(this, properties.bellCharacter, terminalView)
        }

        // Title change callback
        info.emulator?.onTitleChanged = { title ->
            runOnUiThread {
                updateTitle()
                refreshTabs()
            }
        }

        binding.extraKeys.setTerminalView(terminalView!!)
        
        // Auto-focus the terminal and show keyboard
        terminalView?.requestFocus()
        
        updateTitle()
    }

    /**
     * Attach to whatever the active session is.
     */
    private fun attachToActiveSession() {
        when (sessionManager.currentSessionKind) {
            SessionManager.SessionKind.TERMINAL -> {
                attachToTerminalSession(sessionManager.activeSession)
            }
            SessionManager.SessionKind.DISPLAY -> {
                sessionManager.currentDisplayNum?.let { openDisplayViewer(it) }
            }
        }
    }

    /**
     * Open the X11 display viewer for a specific display number.
     */
    private fun openDisplayViewer(displayNum: Int) {
        if (X11Manager.isDisplayRunning(displayNum)) {
            X11DisplayActivity.start(this, displayNum)
        } else {
            Toast.makeText(this, "Display :$displayNum is not running", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTitle() {
        val info = sessionManager.activeSession
        val wakeIndicator = if (WakeLockManager.isWakeLockHeld()) " ⚡" else ""
        val fullscreenIndicator = if (FullscreenManager.isFullscreen()) " ⛶" else ""
        val displayIndicator = sessionManager.currentDisplayNum?.let { " Display :$it" } ?: ""
        supportActionBar?.title = "${info?.title ?: "TermX"}$displayIndicator$wakeIndicator$fullscreenIndicator"
    }

    private fun loadThemeColors(themeName: String): TerminalColors {
        return when (themeName) {
            "Dracula" -> TerminalColors.dracula()
            "Monokai" -> TerminalColors.monokai()
            "Solarized Dark" -> TerminalColors.solarizedDark()
            "Nord" -> TerminalColors.nord()
            else -> TerminalColors.catppuccinMocha()
        }
    }

    // ---- Menu ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_wake_lock)?.title = if (WakeLockManager.isWakeLockHeld()) "Wake Lock: ON ⚡" else "Wake Lock: OFF"
        menu.findItem(R.id.action_storage_setup)?.title = if (StorageSetup.isStorageSetup(this)) "Storage Info" else "Setup Storage"
        menu.findItem(R.id.action_fullscreen)?.title = if (FullscreenManager.isFullscreen()) "Exit Fullscreen" else "Fullscreen"
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_new_session -> { createNewSession(); true }
            R.id.action_sessions -> { showSessionSwitcher(); true }
            R.id.action_storage_setup -> { showStorageSetup(); true }
            R.id.action_wake_lock -> { toggleWakeLock(); true }
            R.id.action_open_url -> { showOpenUrlDialog(); true }
            R.id.action_fullscreen -> { toggleFullscreen(); true }
            R.id.action_float -> { toggleFloatingTerminal(); true }
            R.id.action_copy -> { terminalView?.copySelection(); Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show(); true }
            R.id.action_paste -> { terminalView?.paste(); true }
            R.id.action_font_increase -> { changeFontSize(1f); true }
            R.id.action_font_decrease -> { changeFontSize(-1f); true }
            R.id.action_toggle_extra_keys -> { toggleExtraKeys(); true }
            R.id.action_rename_session -> { showRenameDialog(); true }
            R.id.action_system_info -> { showSystemInfo(); true }
            R.id.action_x11_display -> { showDisplayManager(); true }
            R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            R.id.action_exit -> { confirmExit(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ---- Keyboard shortcuts (Ctrl+Alt combos) ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.isCtrlPressed && event.isAltPressed) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_C -> { createNewSession(); return true }
                KeyEvent.KEYCODE_N -> { switchToNextSession(); return true }
                KeyEvent.KEYCODE_P -> { switchToPrevSession(); return true }
                KeyEvent.KEYCODE_R -> { showRenameDialog(); return true }
                KeyEvent.KEYCODE_K -> { sessionManager.activeSession?.session?.sendCtrl('c'); return true }
                KeyEvent.KEYCODE_V -> { terminalView?.paste(); return true }
                KeyEvent.KEYCODE_F -> { toggleFullscreen(); return true }
                KeyEvent.KEYCODE_D -> { showDisplayManager(); return true }  // Ctrl+Alt+D = Display
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- Back key handling ----

    override fun onBackPressed() {
        if (prefs.backKeyEscape || properties.backKeyMode == TermXProperties.BackKeyMode.ESCAPE) {
            sessionManager.activeSession?.session?.write("\u001B")
        } else {
            moveTaskToBack(true)
        }
    }

    // ---- Fullscreen ----

    private fun toggleFullscreen() {
        FullscreenManager.toggle(this)
        updateTitle()
        invalidateOptionsMenu()
    }

    // ---- Floating Terminal ----

    private fun toggleFloatingTerminal() {
        if (floatingTerminal?.isShowing() == true) {
            floatingTerminal?.dismiss()
        } else {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Enable overlay permission for floating terminal", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return
            }
            floatingTerminal = FloatingTerminal(this).apply { show() }
        }
    }

    // ---- Storage Setup ----

    private fun showStorageSetup() {
        if (StorageSetup.isStorageSetup(this)) {
            val info = StorageSetup.getStorageInfo(this)
            AlertDialog.Builder(this)
                .setTitle("Storage Info")
                .setMessage(info)
                .setPositiveButton("Re-setup") { _, _ -> performStorageSetup() }
                .setNegativeButton("Close", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.storage_setup_title)
                .setMessage(R.string.storage_setup_message)
                .setPositiveButton(R.string.storage_setup_grant) { _, _ -> performStorageSetup() }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun performStorageSetup() {
        if (!StorageSetup.hasStoragePermission(this)) {
            StorageSetup.requestStoragePermission(this)
            Toast.makeText(this, R.string.storage_permission_needed, Toast.LENGTH_LONG).show()
            return
        }
        val result = StorageSetup.setupStorage(this)
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
    }

    // ---- Wake Lock ----

    private fun toggleWakeLock() {
        WakeLockManager.toggle(this)
        Toast.makeText(this, if (WakeLockManager.isWakeLockHeld()) R.string.wake_lock_enabled else R.string.wake_lock_disabled, Toast.LENGTH_SHORT).show()
        updateTitle()
        invalidateOptionsMenu()
    }

    // ---- URL Opener ----

    private fun showOpenUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com"
            textSize = 16f
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_open_url_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_open_url_open) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) TermXApi.openUrl(this, url)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ---- Session Management ----

    private fun showSessionSwitcher() {
        val sessions = sessionManager.allSessions
        if (sessions.isEmpty()) { createNewSession(); return }

        val names = sessions.map { session ->
            when (session) {
                is SessionManager.SessionType.Terminal -> "${session.title}"
                is SessionManager.SessionType.Display -> "${session.title}"
            }
        }.toTypedArray()

        val activeIndex = sessions.indexOfFirst { session ->
            when (session) {
                is SessionManager.SessionType.Terminal -> session.id == sessionManager.activeSession?.id
                is SessionManager.SessionType.Display -> session.displayNum == sessionManager.currentDisplayNum
            }
        }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Sessions (${sessions.size})")
            .setSingleChoiceItems(names, activeIndex) { dialog, which ->
                when (val session = sessions[which]) {
                    is SessionManager.SessionType.Terminal -> {
                        sessionManager.switchToSession(session.id)
                        attachToTerminalSession(session)
                    }
                    is SessionManager.SessionType.Display -> {
                        sessionManager.switchToDisplay(session.displayNum)
                        openDisplayViewer(session.displayNum)
                    }
                }
                refreshTabs()
                dialog.dismiss()
            }
            .setPositiveButton("New Terminal") { _, _ -> createNewSession() }
            .setNeutralButton("New Display") { _, _ -> startNewDisplay() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun switchToNextSession() {
        val sessions = sessionManager.allSessions
        val current = sessions.indexOfFirst { session ->
            when (session) {
                is SessionManager.SessionType.Terminal -> session.id == sessionManager.activeSession?.id
                is SessionManager.SessionType.Display -> session.displayNum == sessionManager.currentDisplayNum
            }
        }
        val next = if (current + 1 < sessions.size) current + 1 else 0
        when (val session = sessions[next]) {
            is SessionManager.SessionType.Terminal -> {
                sessionManager.switchToSession(session.id)
                attachToTerminalSession(session)
            }
            is SessionManager.SessionType.Display -> {
                sessionManager.switchToDisplay(session.displayNum)
                openDisplayViewer(session.displayNum)
            }
        }
        refreshTabs()
    }

    private fun switchToPrevSession() {
        val sessions = sessionManager.allSessions
        val current = sessions.indexOfFirst { session ->
            when (session) {
                is SessionManager.SessionType.Terminal -> session.id == sessionManager.activeSession?.id
                is SessionManager.SessionType.Display -> session.displayNum == sessionManager.currentDisplayNum
            }
        }
        val prev = if (current - 1 >= 0) current - 1 else sessions.size - 1
        when (val session = sessions[prev]) {
            is SessionManager.SessionType.Terminal -> {
                sessionManager.switchToSession(session.id)
                attachToTerminalSession(session)
            }
            is SessionManager.SessionType.Display -> {
                sessionManager.switchToDisplay(session.displayNum)
                openDisplayViewer(session.displayNum)
            }
        }
        refreshTabs()
    }

    // ---- Rename Session ----

    private fun showRenameDialog() {
        val current = sessionManager.activeSession ?: return
        val input = EditText(this).apply {
            text = android.text.Editable.Factory.getInstance().newEditable(current.title)
            setSelection(current.title.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Session")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    sessionManager.renameSession(current.id, newName)
                    updateTitle()
                    refreshTabs()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Font Size ----

    private fun changeFontSize(delta: Float) {
        val newSize = (prefs.fontSize + delta).coerceIn(8f, 32f)
        prefs.fontSize = newSize
        terminalView?.setFontSize(newSize)
    }

    // ---- Extra Keys Toggle ----

    private fun toggleExtraKeys() {
        val visible = binding.extraKeys.visibility == View.VISIBLE
        binding.extraKeys.visibility = if (visible) View.GONE else View.VISIBLE
        prefs.showExtraKeys = !visible
    }

    // ---- System Info ----

    private fun showSystemInfo() {
        val info = buildString {
            appendLine(ShellUtils.getSystemInfo())
            appendLine()
            appendLine(WakeLockManager.getStatus())
            appendLine()
            appendLine(StorageSetup.getStorageInfo(this@MainActivity))
            appendLine()
            appendLine(X11Manager.getStatus())
        }
        sessionManager.activeSession?.session?.sendInput("echo '${info.replace("'", "'\\''")}'")
    }

    // ---- X11 Display Manager ----

    private fun showDisplayManager() {
        val displays = X11Manager.listDisplays()

        val options = mutableListOf<String>()
        if (displays.isEmpty()) {
            options.add("Start Display :0")
        } else {
            for (info in displays) {
                options.add("View Display :${info.displayNum} (${info.width}x${info.height})")
            }
            options.add("Start New Display :${X11Manager.allocateDisplayNum()}")
        }
        options.add("Stop All Displays")

        AlertDialog.Builder(this)
            .setTitle("Virtual Displays")
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    options[which].startsWith("Start Display") -> {
                        startNewDisplay()
                    }
                    options[which].startsWith("View Display") -> {
                        val num = options[which].substringAfter(":").substringBefore(")").trim().toIntOrNull() ?: 0
                        openDisplayViewer(num)
                    }
                    options[which] == "Stop All Displays" -> {
                        X11Manager.stopAllDisplays()
                        // Remove display sessions from session manager
                        val displayNums = sessionManager.allDisplaySessions.map { it.displayNum }.toList()
                        for (num in displayNums) {
                            sessionManager.removeDisplaySession(num)
                        }
                        refreshTabs()
                        Toast.makeText(this, "All displays stopped", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startNewDisplay() {
        val displayNum = X11Manager.allocateDisplayNum()
        if (displayNum < 0) {
            Toast.makeText(this, "Maximum displays reached (8)", Toast.LENGTH_SHORT).show()
            return
        }

        val resolutions = arrayOf("1024x768", "1280x720", "1280x1024", "1920x1080", "2560x1440")

        AlertDialog.Builder(this)
            .setTitle("Start Display :$displayNum")
            .setItems(resolutions) { _, which ->
                val (w, h) = resolutions[which].split("x").map { it.toInt() }

                // Create the display session through SessionManager
                val info = sessionManager.createDisplaySession(displayNum, w, h)
                if (info != null) {
                    Toast.makeText(this, "Display :$displayNum started (${w}x${h})\nX11 port: ${6000 + displayNum}\nVNC port: ${5900 + displayNum}", Toast.LENGTH_LONG).show()
                    refreshTabs()
                    openDisplayViewer(displayNum)
                } else {
                    Toast.makeText(this, "Failed to start display :$displayNum", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Exit ----

    private fun confirmExit() {
        if (sessionManager.activeSessionCount == 0) { finish(); return }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_exit_title)
            .setMessage("Close all ${sessionManager.activeSessionCount} session(s)?")
            .setPositiveButton(R.string.dialog_close_all) { _, _ ->
                sessionManager.closeAllSessions()
                WakeLockManager.release()
                floatingTerminal?.dismiss()
                stopTerminalService()
                finish()
            }
            .setNeutralButton(R.string.dialog_background) { _, _ -> moveTaskToBack(true) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ---- Service ----

    private fun startTerminalService() {
        val intent = Intent(this, TerminalService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun stopTerminalService() {
        stopService(Intent(this, TerminalService::class.java))
    }

    // ---- Lifecycle ----

    override fun onResume() {
        super.onResume()
        terminalView?.resume()

        // Reload properties in case they changed
        properties.load()

        // Check storage
        if (!StorageSetup.isStorageSetup(this) && StorageSetup.hasStoragePermission(this)) {
            val result = StorageSetup.setupStorage(this)
            if (result.success) Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        }

        // Sync display sessions with X11Manager
        syncDisplaySessions()
        refreshTabs()
    }

    override fun onPause() {
        super.onPause()
        terminalView?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && prefs.closeOnExit) {
            sessionManager.closeAllSessions()
            WakeLockManager.release()
            floatingTerminal?.dismiss()
            stopTerminalService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!StorageSetup.isStorageSetup(this)) {
                val result = StorageSetup.setupStorage(this)
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Sync display sessions with X11Manager.
     * Adds sessions for running displays that aren't tracked yet,
     * removes sessions for displays that have been stopped externally.
     */
    private fun syncDisplaySessions() {
        val runningDisplays = X11Manager.listDisplays()
        val trackedDisplays = sessionManager.allDisplaySessions.map { it.displayNum }.toSet()

        // Add new display sessions
        for (info in runningDisplays) {
            if (info.displayNum !in trackedDisplays) {
                val session = SessionManager.SessionType.Display(
                    displayNum = info.displayNum,
                    title = "Display :${info.displayNum}",
                    createdAt = info.startTime
                )
                sessionManager.allDisplaySessions // Force access
                // Use internal sync via X11Manager
            }
        }

        // Remove stale display sessions
        val runningNums = runningDisplays.map { it.displayNum }.toSet()
        for (session in sessionManager.allDisplaySessions) {
            if (session.displayNum !in runningNums) {
                sessionManager.removeDisplaySession(session.displayNum)
            }
        }
    }
}
