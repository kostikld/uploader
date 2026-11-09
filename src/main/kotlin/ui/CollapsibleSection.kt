package org.kavo.uploader.ui

import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * A clickable title that folds its content away. Starts folded, so a section the user may never open takes
 * one line of the dialog until they ask for the rest.
 */
internal class CollapsibleSection(private val titleText: String) : JPanel(BorderLayout()) {
    val body = JPanel(BorderLayout())
    private val header = JLabel()
    private var expanded = false

    /** Notified after a click laid out the new height, so the host can scroll the content into view. */
    var onToggle: ((expanded: Boolean) -> Unit)? = null

    init {
        isOpaque = false
        body.isOpaque = false
        header.font = header.font.deriveFont(Font.BOLD)
        header.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                toggle()
            }
        })
        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        render()
    }

    private fun toggle() {
        expanded = !expanded
        render()
        // The dialog laid its content out for the old height: `revalidate` walks the new preferred
        // size up through the dialog's validation chain so the window makes room for the body.
        revalidate()
        repaint()
        onToggle?.invoke(expanded)
    }

    private fun render() {
        header.text = (if (expanded) "▼ " else "▶ ") + titleText
        body.isVisible = expanded
    }
}
