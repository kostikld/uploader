package org.kavo.uploader.settings

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Row plan for the tool window buttons.
 *
 * A button stays on the current row while at most half of it would be hidden by the panel edge;
 * it moves to a new row once more than that would be hidden.
 */
fun planMenuButtonRows(
    widths: List<Int>,
    available: Int,
    gap: Int,
    wrap: Boolean,
    hiddenThreshold: Double = MENU_BUTTONS_CLIP_THRESHOLD,
): List<List<Int>> {
    if (widths.isEmpty()) return listOf()
    if (!wrap || available <= 0) return listOf(widths.indices.toList())
    val rows = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    var x = 0
    widths.forEachIndexed { index, width ->
        if (current.isNotEmpty()) {
            val hidden = x + width - available
            if (hidden > 0 && hidden > width * hiddenThreshold) {
                rows.add(current.toList())
                current = mutableListOf()
                x = 0
            }
        }
        current.add(index)
        x += width + gap
    }
    rows.add(current.toList())
    return rows
}

class MenuButtonsPanel(
    private val wrap: Boolean,
    private val gap: Int = MENU_BUTTONS_GAP,
) : JPanel(MenuButtonsLayout(wrap, gap)) {
    fun addMenuButton(button: JButton) {
        add(button)
    }
}

private class MenuButtonsLayout(
    private val wrap: Boolean,
    private val gap: Int,
) : LayoutManager {
    private var requestedHeight = -1

    override fun addLayoutComponent(name: String, component: Component) {}

    override fun removeLayoutComponent(component: Component) {}

    override fun preferredLayoutSize(container: Container): Dimension {
        val rows = rowsIn(container)
        return Dimension(
            rows.map { rowWidth(it) }.maxOrDefault(0),
            rows.map { rowHeight(it) }.sum() + gap * (rows.size - 1).coerceAtLeast(0),
        )
    }

    override fun minimumLayoutSize(container: Container): Dimension {
        val heights = container.getComponents().toList().map { it.getPreferredSize().height }
        return Dimension(0, heights.maxOrDefault(0))
    }

    override fun layoutContainer(container: Container) {
        val rows = rowsIn(container)
        var y = 0
        rows.forEach { row ->
            val height = rowHeight(row)
            var x = 0
            row.forEach { component ->
                component.setBounds(x, y, component.getPreferredSize().width, height)
                x += component.getPreferredSize().width + gap
            }
            y += height + gap
        }
        val requiredHeight = (y - gap).coerceAtLeast(0)
        if (requiredHeight != container.height && requiredHeight != requestedHeight && container.parent != null) {
            // Ask the surrounding layout for the height the wrapped rows need, otherwise the extra
            // rows are laid out outside the panel and stay invisible. The remembered height keeps
            // this from repeating when the surrounding layout cannot give the space.
            requestedHeight = requiredHeight
            container.invalidate()
            container.validate()
        }
    }

    private fun rowsIn(container: Container): List<List<Component>> {
        val components = container.getComponents().toList()
        val widths = components.map { it.getPreferredSize().width }
        return planMenuButtonRows(widths, container.width, gap, wrap)
            .map { row -> row.map { index -> components[index] } }
    }

    private fun rowWidth(row: List<Component>): Int =
        row.map { it.getPreferredSize().width }.sum() + gap * (row.size - 1).coerceAtLeast(0)

    private fun rowHeight(row: List<Component>): Int =
        row.map { it.getPreferredSize().height }.maxOrDefault(0)
}

private fun <T : Comparable<T>> List<T>.maxOrDefault(default: T): T =
    if (isEmpty()) default else max()
