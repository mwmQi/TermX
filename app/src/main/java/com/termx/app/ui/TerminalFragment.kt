package com.termx.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.termx.app.keyboard.ExtraKeysView
import com.termx.app.session.SessionManager
import com.termx.app.terminal.TerminalView

/**
 * Fragment representing a single terminal session page.
 * Each page contains a TerminalView connected to a session.
 */
class TerminalFragment : Fragment() {

    companion object {
        private const val ARG_SESSION_ID = "session_id"

        fun newInstance(sessionId: String): TerminalFragment {
            return TerminalFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SESSION_ID, sessionId)
                }
            }
        }
    }

    private var terminalView: TerminalView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val sessionId = arguments?.getString(ARG_SESSION_ID) ?: return View(context)
        val sessionManager = context?.let { SessionManager.getInstance(it) } ?: return View(context)
        val info = sessionManager.getSession(sessionId) ?: return View(context)

        terminalView = TerminalView(requireContext()).apply {
            buffer = info.buffer
            session = info.session
            emulator = info.emulator
            colors = com.termx.app.terminal.TerminalColors.catppuccinMocha()
            setFontSize(14f)
        }

        return FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(terminalView)
        }
    }

    override fun onResume() {
        super.onResume()
        terminalView?.resume()
    }

    override fun onPause() {
        super.onPause()
        terminalView?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        terminalView = null
    }

    fun getTerminalView(): TerminalView? = terminalView
}
