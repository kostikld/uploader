package org.kavo.uploader.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import org.kavo.uploader.ImportLinesDialog
import org.kavo.uploader.MyMessageBundle
import org.kavo.uploader.UploaderNotifications
import org.kavo.uploader.settings.PathMapping
import org.kavo.uploader.settings.ServerAction
import org.kavo.uploader.settings.cloneAction
import org.kavo.uploader.transfer.ProfileTransfer
import org.kavo.uploader.upload.PathMappingResolver
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import java.awt.datatransfer.StringSelection
import java.util.UUID
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

private fun copyTablesClipboard(text: String) {
    CopyPasteManager.getInstance().setContents(StringSelection(text))
}

// A row that overrides an inherited facility entry: light goldenrod in the light theme, dark olive in the dark.
private val overrideBackground = JBColor(Color(255, 243, 179), Color(101, 79, 0))

/**
 * A scroll pane that sizes its height to the table's rows instead of a fixed block: the rows plus
 * `emptyRowsBelow` empty ones underneath - room for typing when the table is edited, none when it is
 * only shown. At least one row tall, and tall lists still scroll inside the dialog.
 */
internal class RowsAwareScrollPane(
    private val table: JTable,
    private val preferredWidth: Int,
    private val emptyRowsBelow: Int = EMPTY_ROWS_BELOW,
) : JBScrollPane(table) {
    override fun getPreferredSize(): Dimension {
        val header = table.tableHeader?.preferredSize?.height ?: 0
        // The room kept under the last row is where the next entry gets typed.
        val rows = maxOf(1, table.rowCount + emptyRowsBelow)
        val height = header + rows * table.rowHeight + insets.top + insets.bottom
        return Dimension(preferredWidth, height)
    }

    private companion object {
        const val EMPTY_ROWS_BELOW = 3
    }
}

/** A cell renderer that paints the rows the host marked as overrides on a highlight background. */
private class OverrideCellRenderer(private val isOverride: (Int) -> Boolean) : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        component.background = when {
            isSelected -> table.selectionBackground
            isOverride(row) -> overrideBackground
            else -> table.background
        }
        return component
    }
}

/**
 * The two tables a server or a facility edits: path mappings and actions, each with Add, Remove, Copy and
 * Import under it. A host dialog seeds the rows, reads them back with `mappings()` and `actions()`, and
 * calls `stopEditing()` before reading. The server dialog sets `mappingOverrides` and `actionOverrides`
 * when the server picked a facility: the keys the facility also defines. Own rows repeating one of them
 * are highlighted, because they replace the facility's entry.
 */
internal class MappingActionTables(private val project: Project) {
    val mappingsModel = object : DefaultTableModel(
        arrayOf(
            MyMessageBundle.message("mapping.local"),
            MyMessageBundle.message("mapping.remote"),
        ),
        0,
    ) {
        override fun isCellEditable(row: Int, column: Int) = true
    }
    val mappingsTable: JBTable = object : JBTable(mappingsModel) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            if (row >= 0 && mappingOverridesRow(row)) return MyMessageBundle.message("facility.override.mapping")
            return super.getToolTipText(event)
        }
    }.apply {
        toolTipText = MyMessageBundle.message("server.mappings.tooltip")
        installTableShortcuts(this, mappingsModel) { rows ->
            rows.map { row -> mappingCells(row) }
        }
        setDefaultRenderer(Any::class.java, OverrideCellRenderer { row -> mappingOverridesRow(row) })
    }
    val actionsModel = object : DefaultTableModel(
        arrayOf(
            MyMessageBundle.message("server.action.name"),
            MyMessageBundle.message("server.action.command"),
            "id",
        ),
        0,
    ) {
        override fun isCellEditable(row: Int, column: Int) = column < 2
    }
    val actionsTable: JBTable = object : JBTable(actionsModel) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            if (row >= 0 && actionOverridesRow(row)) return MyMessageBundle.message("facility.override.action")
            return super.getToolTipText(event)
        }
    }.apply {
        columnModel.removeColumn(columnModel.getColumn(2))
        installTableShortcuts(this, actionsModel) { rows ->
            val takenNames = actions().map { it.name }.toMutableSet()
            rows.map { row -> actionCells(cloneAction(actionAt(row), takenNames)) }
        }
        setDefaultRenderer(Any::class.java, OverrideCellRenderer { row -> actionOverridesRow(row) })
    }

    /** The normalized local paths an inherited facility maps; an own row repeating one overrides it. */
    var mappingOverrides: Set<String> = emptySet()
        set(value) {
            field = value
            mappingsTable.repaint()
        }

    /** The action names an inherited facility defines; an own row repeating one overrides it. */
    var actionOverrides: Set<String> = emptySet()
        set(value) {
            field = value
            actionsTable.repaint()
        }

    fun mappingPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(JLabel(MyMessageBundle.message("server.mappings.hint")), BorderLayout.NORTH)
        add(RowsAwareScrollPane(mappingsTable, 680), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton(MyMessageBundle.message("mapping.add")).apply {
                addActionListener { mappingsModel.addRow(arrayOf("", "")) }
            })
            add(JButton(MyMessageBundle.message("mapping.remove")).apply {
                addActionListener {
                    mappingsTable.selectedRows.sortedDescending().forEach(mappingsModel::removeRow)
                }
            })
            add(JButton(MyMessageBundle.message("mapping.copy")).apply {
                addActionListener {
                    stopEditing()
                    val mappings = mappings()
                    copyTablesClipboard(ProfileTransfer.encodeMappings(mappings))
                    UploaderNotifications.info(
                        project,
                        MyMessageBundle.message("mapping.copied", mappings.size),
                    )
                }
            })
            add(JButton(MyMessageBundle.message("mapping.import")).apply {
                addActionListener { importMappings() }
            })
        }, BorderLayout.SOUTH)
    }

    fun actionsPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(RowsAwareScrollPane(actionsTable, 680), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton(MyMessageBundle.message("server.action.add")).apply {
                addActionListener { actionsModel.addRow(arrayOf("", "", UUID.randomUUID().toString())) }
            })
            add(JButton(MyMessageBundle.message("server.action.remove")).apply {
                addActionListener {
                    actionsTable.selectedRows.sortedDescending().forEach(actionsModel::removeRow)
                }
            })
            add(JButton(MyMessageBundle.message("server.action.copy")).apply {
                addActionListener {
                    stopEditing()
                    val actions = actions()
                    copyTablesClipboard(ProfileTransfer.encodeActions(actions))
                    UploaderNotifications.info(
                        project,
                        MyMessageBundle.message("server.action.copied", actions.size),
                    )
                }
            })
            add(JButton(MyMessageBundle.message("server.action.import")).apply {
                addActionListener { importActions() }
            })
        }, BorderLayout.SOUTH)
    }

    fun mappings(): List<PathMapping> =
        (0 until mappingsModel.rowCount).map { row -> pathMapping(row) }
            .filterNot { it.localPath.isBlank() && it.remotePath.isBlank() }

    fun actions(): List<ServerAction> =
        (0 until actionsModel.rowCount).map { row -> actionAt(row) }

    fun stopEditing() {
        if (mappingsTable.isEditing) mappingsTable.cellEditor.stopCellEditing()
        if (actionsTable.isEditing) actionsTable.cellEditor.stopCellEditing()
    }

    private fun mappingOverridesRow(row: Int): Boolean {
        if (mappingOverrides.isEmpty()) return false
        val local = PathMappingResolver.normalizeLocal(mappingsModel.getValueAt(row, 0)?.toString().orEmpty())
        return local.isNotEmpty() && local in mappingOverrides
    }

    private fun actionOverridesRow(row: Int): Boolean {
        if (actionOverrides.isEmpty()) return false
        val name = actionsModel.getValueAt(row, 0)?.toString().orEmpty().trim()
        return name.isNotEmpty() && name in actionOverrides
    }

    private fun importMappings() {
        val dialog = ImportLinesDialog(
            project,
            MyMessageBundle.message("mapping.import"),
            MyMessageBundle.message("import.mappings.hint"),
        )
        if (!dialog.showAndGet()) return
        val mappings = ProfileTransfer.decodeMappings(dialog.text())
        mappings.forEach { mappingsModel.addRow(arrayOf(it.localPath, it.remotePath)) }
        UploaderNotifications.info(project, MyMessageBundle.message("mapping.imported", mappings.size))
    }

    private fun importActions() {
        val dialog = ImportLinesDialog(
            project,
            MyMessageBundle.message("server.action.import"),
            MyMessageBundle.message("import.actions.hint"),
        )
        if (!dialog.showAndGet()) return
        val actions = ProfileTransfer.decodeActions(dialog.text())
        actions.forEach { actionsModel.addRow(arrayOf(it.name, it.command, it.id)) }
        UploaderNotifications.info(project, MyMessageBundle.message("server.action.imported", actions.size))
    }

    private fun pathMapping(row: Int): PathMapping =
        PathMapping(
            localPath = PathMappingResolver.normalizeLocal(mappingsModel.getValueAt(row, 0)?.toString().orEmpty()),
            remotePath = mappingsModel.getValueAt(row, 1)?.toString().orEmpty().trim(),
        )

    private fun mappingCells(row: Int): Array<String> =
        arrayOf(
            mappingsModel.getValueAt(row, 0)?.toString().orEmpty(),
            mappingsModel.getValueAt(row, 1)?.toString().orEmpty(),
        )

    private fun actionAt(row: Int): ServerAction =
        ServerAction(
            id = actionsModel.getValueAt(row, 2)?.toString().orEmpty(),
            name = actionsModel.getValueAt(row, 0)?.toString().orEmpty().trim(),
            command = actionsModel.getValueAt(row, 1)?.toString().orEmpty().trim(),
        )

    private fun actionCells(action: ServerAction): Array<String> =
        arrayOf(action.name, action.command, action.id)
}
