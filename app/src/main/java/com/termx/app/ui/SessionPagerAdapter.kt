package com.termx.app.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.termx.app.session.SessionManager

/**
 * ViewPager2 adapter that manages terminal session fragments.
 * Supports swipe to switch between sessions.
 */
class SessionPagerAdapter(
    activity: FragmentActivity,
    private var sessions: List<SessionManager.SessionInfo> = emptyList()
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = sessions.size

    override fun createFragment(position: Int): Fragment {
        val sessionId = sessions[position].id
        return TerminalFragment.newInstance(sessionId)
    }

    fun updateSessions(newSessions: List<SessionManager.SessionInfo>) {
        val oldSize = sessions.size
        sessions = newSessions
        if (oldSize != newSessions.size) {
            notifyDataSetChanged()
        }
    }

    fun getSessionId(position: Int): String? {
        return if (position in sessions.indices) sessions[position].id else null
    }
}
