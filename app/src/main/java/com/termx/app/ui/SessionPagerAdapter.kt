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
    private var sessions: List<SessionManager.SessionType> = emptyList()
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = sessions.size

    override fun createFragment(position: Int): Fragment {
        val session = sessions[position]
        val sessionId = when (session) {
            is SessionManager.SessionType.Terminal -> session.id
            is SessionManager.SessionType.Display -> "display_${session.displayNum}"
        }
        return TerminalFragment.newInstance(sessionId)
    }

    fun updateSessions(newSessions: List<SessionManager.SessionType>) {
        val oldSize = sessions.size
        sessions = newSessions
        if (oldSize != newSessions.size) {
            notifyDataSetChanged()
        }
    }

    fun getSessionId(position: Int): String? {
        return if (position in sessions.indices) {
            when (val session = sessions[position]) {
                is SessionManager.SessionType.Terminal -> session.id
                is SessionManager.SessionType.Display -> "display_${session.displayNum}"
            }
        } else null
    }
}
