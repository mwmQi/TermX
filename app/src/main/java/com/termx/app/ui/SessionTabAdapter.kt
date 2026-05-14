package com.termx.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.termx.app.R

/**
 * Adapter for session tabs — supports both terminal and display sessions.
 *
 * Display sessions are visually distinguished with a colored indicator
 * and display number tag (e.g., ":0", ":1").
 */
class SessionTabAdapter(
    private var items: List<TabItem> = emptyList(),
    private val onTabClicked: (Int) -> Unit,
    private val onTabClosed: (Int) -> Unit
) : RecyclerView.Adapter<SessionTabAdapter.TabViewHolder>() {

    private var selectedPosition: Int = 0

    data class TabItem(
        val id: String,
        val title: String,
        val type: TabType
    )

    enum class TabType { TERMINAL, DISPLAY }

    inner class TabViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleText: TextView = view.findViewById(R.id.tab_title)
        val closeBtn: View = view.findViewById(R.id.tab_close)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session_tab, parent, false)
        return TabViewHolder(view)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val item = items[position]
        val isSelected = position == selectedPosition

        // Set title with display tag prefix for display sessions
        holder.titleText.text = when (item.type) {
            TabType.DISPLAY -> "\u25A0 ${item.title}"  // ■ Display :0
            TabType.TERMINAL -> item.title
        }

        // Style based on type and selection
        holder.titleText.isSelected = isSelected
        holder.titleText.setTypeface(
            when (item.type) {
                TabType.DISPLAY -> Typeface.MONOSPACE
                TabType.TERMINAL -> Typeface.DEFAULT
            },
            if (isSelected) Typeface.BOLD else Typeface.NORMAL
        )

        // Color coding
        when (item.type) {
            TabType.DISPLAY -> {
                holder.titleText.setTextColor(
                    if (isSelected) Color.parseColor("#89B4FA")  // Blue for active display
                    else Color.parseColor("#6C7086")              // Muted for inactive
                )
            }
            TabType.TERMINAL -> {
                holder.titleText.setTextColor(
                    if (isSelected) Color.WHITE
                    else Color.parseColor("#6C7086")
                )
            }
        }

        holder.titleText.setOnClickListener {
            selectedPosition = position
            onTabClicked(position)
            notifyDataSetChanged()
        }

        holder.closeBtn.setOnClickListener {
            onTabClosed(position)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<TabItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setSelectedPosition(pos: Int) {
        selectedPosition = pos
        notifyDataSetChanged()
    }

    // Backward compatibility — converts old SessionInfo list to TabItem list
    fun updateSessions(sessions: List<com.termx.app.session.SessionManager.SessionType>) {
        items = sessions.map { session ->
            when (session) {
                is com.termx.app.session.SessionManager.SessionType.Terminal ->
                    TabItem(id = session.id, title = session.title, type = TabType.TERMINAL)
                is com.termx.app.session.SessionManager.SessionType.Display ->
                    TabItem(id = "display_${session.displayNum}", title = session.title, type = TabType.DISPLAY)
            }
        }
        notifyDataSetChanged()
    }
}
