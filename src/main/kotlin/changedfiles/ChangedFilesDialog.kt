package org.kavo.uploader.changedfiles

import com.intellij.ide.setToolTipText
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import org.kavo.uploader.MyMessageBundle
import org.kavo.uploader.settings.PathMapping
import org.kavo.uploader.settings.ServerProfile
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane

data class ChangedFilesData(
    val rows: List<UploadRow>,
    val projectRoot: java.nio.file.Path,
    val servers: List<ServerProfile>,
)

class ChangedFilesDialog(
    project: Project,
    private val data: ChangedFilesData,
) : DialogWrapper(project, true) {
    private var server: ServerProfile? = null
    private var mappings: List<PathMapping> = emptyList()
    private val checks: MutableList<JCheckBox> = mutableListOf()
    private var result: List<UploadRow> = emptyList()

    private val serverButton = JButton()

    init {
        title = MyMessageBundle.message("changed.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val listPanel = Box.createVerticalBox()
        data.rows.forEach { uploadRow ->
            val check = JCheckBox(uploadRow.displayPath)
            checks.add(check)
            listPanel.add(check)
        }

        if (data.servers.isNotEmpty()) server = data.servers.first()
        mappings = server?.mappings.orEmpty()

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
        applyState()

        val north = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel(MyMessageBundle.message("changed.server")))
            add(serverButton)
        }
        val outer = JPanel(BorderLayout())
        outer.add(north, BorderLayout.NORTH)
        outer.add(JScrollPane(listPanel), BorderLayout.CENTER)
        outer.preferredSize = Dimension(560, 360)
        return outer
    }

    override fun doOKAction() {
        result = data.rows.filterIndexed { index, uploadRow ->
            checks.getOrNull(index)?.isSelected == true &&
                    rowIsEnabled(uploadRow, data.projectRoot, mappings)
        }
        super.doOKAction()
    }

    fun result(): List<UploadRow> = result

    fun selectedServer(): ServerProfile? = server

    private fun applyState() {
        data.rows.forEachIndexed { index, uploadRow ->
            val check = checks[index]
            val enabled = rowIsEnabled(uploadRow, data.projectRoot, mappings)
            check.isEnabled = enabled
            check.isSelected = enabled
            check.setToolTipText(
                when {
                    uploadRow.missingClass -> MyMessageBundle.message("changed.tooltip.please.compile")
                    !enabled -> MyMessageBundle.message("changed.tooltip.no.mapping")
                    else -> null
                }?.let {
                    HtmlChunk.text(
                        it
                    )
                }
            )
        }
    }
}

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
