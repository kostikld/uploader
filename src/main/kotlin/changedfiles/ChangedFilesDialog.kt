package org.kavo.uploader.changedfiles

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import org.kavo.uploader.MyMessageBundle
import org.kavo.uploader.settings.PathMapping
import org.kavo.uploader.settings.ServerProfile
import org.kavo.uploader.settings.ServerType
import java.awt.*
import java.nio.file.Path
import javax.swing.*

data class ChangedFilesData(
    val rows: List<UploadRow>,
    val derivedRows: List<UploadRow>,
    val projectRoot: Path,
    val servers: List<ServerProfile>,
    // The profile the dialog has to start and come back with: the one selected in the tool window when the
    // scan started, and after the constants analysis the one the user picked in the dialog it just closed.
    val selectedServer: ServerProfile? = null,
    val zip: Boolean = false,
    val archiveName: String = "",
)

class ChangedFilesDialog(
    project: Project,
    private val data: ChangedFilesData,
    private val preselectedPaths: Set<String>? = null,
    private val canAnalyzeConstants: Boolean = false,
) : DialogWrapper(project, true) {
    private var server: ServerProfile? = null
    private var mappings: List<PathMapping> = emptyList()
    private val rowsByCheck: MutableList<RowCheck> = mutableListOf()

    // A file that is listed twice - changed on its own and reading a changed constant - is picked once.
    private val picked: MutableSet<String> = mutableSetOf()
    private var result: List<UploadRow> = emptyList()
    private var analysisRequested = false

    private val serverButton = JButton()
    private val analyzeButton = JButton(MyMessageBundle.message("changed.analyze_selected")).apply {
        toolTipText = MyMessageBundle.message("changed.analyze_selected.tooltip")
    }
    private val zipCheckbox = JCheckBox(MyMessageBundle.message("changed.zip"))
    private val zipLabel = JBLabel(MyMessageBundle.message("changed.zip"))
    private val archiveNameField = com.intellij.ui.components.JBTextField()
    private val archiveLabel = JBLabel(MyMessageBundle.message("changed.zip.name"))
    private val north = JPanel(FlowLayout(FlowLayout.LEFT))

    init {
        title = MyMessageBundle.message("changed.title")
        archiveNameField.isEnabled = false
        zipLabel.setVisible(false)
        zipCheckbox.setVisible(false)
        archiveLabel.setVisible(false)
        archiveNameField.setVisible(false)
        analyzeButton.setVisible(false)
        zipCheckbox.addActionListener {
            archiveNameField.isEnabled = localServerSelected() && zipCheckbox.isSelected
        }
        analyzeButton.addActionListener { requestConstantAnalysis() }
        if (data.zip) zipCheckbox.isSelected = true
        if (data.archiveName.isNotEmpty()) archiveNameField.text = data.archiveName
        init()
    }

    override fun createCenterPanel(): JComponent {
        // A vertical stack at the natural height of every row: the list starts right under the buttons and
        // stays left aligned, whatever its length.
        val listPanel = JPanel(VerticalLayout(2, SwingConstants.LEFT))
        if (data.derivedRows.isEmpty()) {
            addRows(listPanel, data.rows)
        } else {
            // The first group changed in git; the second one only changed because it reads a constant of
            // the first group, and the compiler copied that value into its bytecode.
            listPanel.add(sectionTitle("changed.section.git_changes"))
            addRows(listPanel, data.rows)
            listPanel.add(spacer())
            listPanel.add(sectionTitle("changed.section.constant_dependents"))
            addRows(listPanel, data.derivedRows)
        }

        server = initialServer(data.servers, data.selectedServer)
        mappings = server?.mappings.orEmpty()
        rowsByCheck.forEach { entry ->
            val key = rowKey(entry.row)
            if (rowIsEnabled(entry.row, data.projectRoot, mappings) &&
                (preselectedPaths == null || preselectedPaths.contains(key))
            ) {
                picked.add(key)
            }
        }

        serverButton.text = server?.name.orEmpty()
        serverButton.addActionListener {
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(data.servers)
                .setRenderer(ServerNameRenderer())
                .setItemChosenCallback { next ->
                    server = next
                    mappings = next?.mappings.orEmpty()
                    serverButton.text = next?.name.orEmpty()
                    applyState()
                }
                .createPopup()
                .show(RelativePoint.getSouthOf(serverButton))
        }
        north.add(JBLabel(MyMessageBundle.message("changed.server")))
        north.add(serverButton)
        north.add(analyzeButton)
        north.add(zipLabel)
        north.add(zipCheckbox)
        north.add(archiveLabel)
        north.add(archiveNameField)
        applyState()

        val outer = JPanel(BorderLayout())
        outer.add(north, BorderLayout.NORTH)
        outer.add(JScrollPane(listPanel), BorderLayout.CENTER)
        outer.preferredSize = Dimension(560, 360)
        return outer
    }

    /** The heading of one group of the list: bold text with space around it. */
    private fun sectionTitle(key: String): JBLabel =
        JBLabel(MyMessageBundle.message(key)).apply { font = font.deriveFont(Font.BOLD) }

    /** An empty row of a known height, used to keep the two groups apart. */
    private fun spacer(): JPanel = JPanel().apply { preferredSize = Dimension(0, JBUI.scale(6)) }

    private fun addRows(panel: JComponent, rows: List<UploadRow>) {
        rows.forEach { row ->
            val check = JCheckBox(row.displayPath)
            check.addActionListener {
                val key = rowKey(row)
                if (picked.contains(key)) {
                    picked.remove(key)
                } else {
                    picked.add(key)
                }
                applyState()
            }
            rowsByCheck.add(RowCheck(row, check))
            panel.add(check)
        }
    }

    /**
     * Closes the dialog and asks the launcher to look for the classes that read a constant whose value changed.
     * Nothing is uploaded here: the analysis only widens the list, and the dialog is asked again.
     */
    private fun requestConstantAnalysis() {
        analysisRequested = true
        doOKAction()
    }

    override fun doOKAction() {
        result = dedupeRows(
            (data.rows + data.derivedRows).filter { row ->
                picked.contains(rowKey(row)) && rowIsEnabled(row, data.projectRoot, mappings)
            },
        )
        super.doOKAction()
    }

    fun result(): List<UploadRow> = result

    fun selectedServer(): ServerProfile? = server

    /** The keys of the picked rows, so the reopened dialog can start with the same ones checked. */
    fun pickedKeys(): Set<String> = picked.toSet()

    /**
     * Whether the "Analyze Constants Changes" button, rather than OK, closed the dialog. Nothing is uploaded
     * when this is true; the dialog is asked again with the classes that were found.
     */
    fun analyzeRequested(): Boolean = analysisRequested

    /**
     * The Java files the analysis looks at: every Java file of the list, checked or not.
     *
     * A constant's own file often cannot be uploaded - its compiled class may have no mapping for the chosen
     * server - while the classes reading it do, so limiting the scan to the checked rows would miss them.
     */
    fun analyzedJavaFiles(): List<Path> = javaSources(data.rows + data.derivedRows)

    fun zipSelected(): Boolean = localServerSelected() && zipCheckbox.isSelected

    fun archiveName(): String = archiveNameField.text.trim()

    private fun localServerSelected(): Boolean = server?.type == ServerType.LOCAL

    private fun applyState() {
        val local = localServerSelected()
        zipLabel.setVisible(local)
        zipCheckbox.setVisible(local)
        archiveLabel.setVisible(local)
        archiveNameField.setVisible(local)
        // A list without a single Java file has no constant whose value could have been copied.
        analyzeButton.setVisible(canAnalyzeConstants && hasJavaSource())
        zipCheckbox.isEnabled = local
        zipCheckbox.isSelected = local && zipCheckbox.isSelected
        archiveNameField.isEnabled = local && zipCheckbox.isSelected
        rowsByCheck.forEach { entry ->
            val check = entry.check
            val enabled = rowIsEnabled(entry.row, data.projectRoot, mappings)
            check.isEnabled = enabled
            check.isSelected = enabled && picked.contains(rowKey(entry.row))
            check.toolTipText =
                when {
                    entry.row.missingClass -> MyMessageBundle.message("changed.tooltip.please.compile")
                    !enabled -> MyMessageBundle.message("changed.tooltip.no.mapping")
                    entry.row.reason != null ->
                        MyMessageBundle.message(
                            "changed.tooltip.constant",
                            entry.row.reason.replace("'", "''"),
                        )

                    else -> null
                }
        }
    }

    /** Whether any listed row comes from a Java file, which is what a compile-time constant needs. */
    private fun hasJavaSource(): Boolean = (data.rows + data.derivedRows).any { row -> isJavaSource(row) }
}

private class RowCheck(val row: UploadRow, val check: JCheckBox)

private class ServerNameRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
        component.text = (value as? ServerProfile)?.name.orEmpty()
        return component
    }
}
