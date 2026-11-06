package org.kavo.uploader.ui

import com.intellij.openapi.ide.CopyPasteManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.InputEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.table.DefaultTableModel

private val menuShortcutMask: Int
    get() = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx

// The editor's "Duplicate Line or Selection" key clones the selected server, path mapping, fnmatch
// pattern or action. The menu mask is Cmd on macOS, so the Ctrl variant is bound as a second keystroke.
private val cloneKeyStrokes: List<KeyStroke>
    get() = listOf(
        KeyStroke.getKeyStroke(KeyEvent.VK_D, menuShortcutMask),
        KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_MASK),
    )

/**
 * Binds the clone keystrokes on any focused component, the same way IntelliJ's editor duplicates a line:
 * `Cmd+D` on macOS, `Ctrl+D` on Windows and Linux.
 */
fun installCloneShortcut(component: JComponent, cloneSelection: () -> Any) {
    cloneKeyStrokes.forEach { keystroke ->
        component.getInputMap(JComponent.WHEN_FOCUSED).put(keystroke, "cloneSelection")
    }
    component.actionMap.put("cloneSelection", object : AbstractAction() {
        override fun actionPerformed(event: ActionEvent) {
            cloneSelection()
        }
    })
}

fun installTableShortcuts(
    table: JTable,
    model: DefaultTableModel,
    cloneRows: (rows: List<Int>) -> List<Array<String>>,
) {
    val inputMap = table.getInputMap(JComponent.WHEN_FOCUSED)
    val actionMap = table.actionMap

    installRowDeleteShortcut(table, model)

    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menuShortcutMask), "copySelectedCell")
    actionMap.put("copySelectedCell", object : AbstractAction() {
        override fun actionPerformed(event: ActionEvent) {
            val row = table.selectedRow
            val column = table.selectedColumn
            if (row < 0 || column < 0) return
            val value = table.getValueAt(row, column)?.toString().orEmpty()
            CopyPasteManager.getInstance().setContents(StringSelection(value))
        }
    })

    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, menuShortcutMask), "pasteSelectedCell")
    actionMap.put("pasteSelectedCell", object : AbstractAction() {
        override fun actionPerformed(event: ActionEvent) {
            val row = table.selectedRow
            val column = table.selectedColumn
            if (row < 0 || column < 0) return
            val value = CopyPasteManager.getInstance()
                .getContents<String>(DataFlavor.stringFlavor)
                ?.trim()
                ?: return
            table.setValueAt(value, row, column)
        }
    })

    installCloneShortcut(table) {
        cloneSelectedRows(table, model, cloneRows)
    }
}

/**
 * `Delete` and `Backspace` remove the selected rows, the same keys that remove a selected server
 * or folder in the tool window. While a cell is being edited those keys belong to the editor, so
 * typing inside a cell is left alone.
 */
private fun installRowDeleteShortcut(table: JTable, model: DefaultTableModel) {
    table.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(event: KeyEvent) {
            if (event.keyCode != KeyEvent.VK_DELETE && event.keyCode != KeyEvent.VK_BACK_SPACE) return
            if (table.isEditing) return
            val rows = table.selectedRows.sortedDescending()
            if (rows.isEmpty()) return
            event.consume()
            rows.forEach(model::removeRow)
            table.repaint()
        }
    })
}

private fun cloneSelectedRows(
    table: JTable,
    model: DefaultTableModel,
    cloneRows: (rows: List<Int>) -> List<Array<String>>,
) {
    if (table.isEditing) table.cellEditor.stopCellEditing()
    val rows = table.selectedRows.sorted()
    if (rows.isEmpty()) return
    val clones = cloneRows(rows)
    if (clones.isEmpty()) return
    val firstClone = rows.last() + 1
    clones.forEachIndexed { offset, cells ->
        model.insertRow(firstClone + offset, cells)
    }
    table.clearSelection()
    table.setRowSelectionInterval(firstClone, firstClone + clones.size - 1)
    table.repaint()
}
