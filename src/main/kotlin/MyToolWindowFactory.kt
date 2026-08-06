package org.kavo.uploader

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.util.ui.FormBuilder
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import org.kavo.uploader.settings.PasswordStore
import org.kavo.uploader.settings.PathMapping
import org.kavo.uploader.settings.ServerProfile
import org.kavo.uploader.settings.SftpSettings
import org.kavo.uploader.upload.PasswordAuthentication
import org.kavo.uploader.upload.PathMappingResolver
import org.kavo.uploader.upload.SftpUploadService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.util.UUID
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.SpinnerNumberModel
import javax.swing.table.DefaultTableModel

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance().createContent(ServerProfilesPanel(project), null, false)
        toolWindow.contentManager.addContent(content)
    }
}

private class ServerProfilesPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val settings = SftpSettings.getInstance()
    private val model = DefaultListModel<ServerProfile>()
    private val serverList = JBList(model).apply {
        cellRenderer = ServerRenderer()
    }

    init {
        add(JBScrollPane(serverList), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton(MyMessageBundle.message("server.add")).apply {
                addActionListener { editProfile(null) }
            })
            add(JButton(MyMessageBundle.message("server.edit")).apply {
                addActionListener { serverList.selectedValue?.let(::editProfile) }
            })
            add(JButton(MyMessageBundle.message("server.remove")).apply {
                addActionListener {
                    serverList.selectedValue?.let {
                        settings.remove(it.id)
                        refresh()
                    }
                }
            })
            add(JButton(MyMessageBundle.message("server.test")).apply {
                addActionListener { serverList.selectedValue?.let(::testConnection) }
            })
        }, BorderLayout.SOUTH)
        refresh()
    }

    private fun refresh() {
        model.clear()
        settings.servers().forEach(model::addElement)
    }

    private fun editProfile(existing: ServerProfile?) {
        val dialog = ServerProfileDialog(project, existing)
        if (!dialog.showAndGet()) return
        val profile = dialog.profile()
        settings.save(profile)
        PasswordStore.set(profile.id, profile.username, dialog.password())
        refresh()
    }

    private fun testConnection(profile: ServerProfile) {
        val password = PasswordStore.get(profile.id)?.toByteArray()
        if (password == null) {
            UploaderNotifications.error(project, MyMessageBundle.message("error.password.missing", profile.name))
            return
        }

        object : Task.Backgroundable(project, MyMessageBundle.message("connection.testing", profile.name), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    ApplicationManager.getApplication().getService(SftpUploadService::class.java)
                        .testConnection(profile, PasswordAuthentication(password))
                    UploaderNotifications.info(project, MyMessageBundle.message("connection.success", profile.name))
                } catch (error: Exception) {
                    UploaderNotifications.error(
                        project,
                        MyMessageBundle.message("connection.failed", profile.name, error.message ?: error.javaClass.simpleName),
                    )
                }
            }
        }.queue()
    }
}

private class ServerRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
        val profile = value as? ServerProfile
        component.text = profile?.let { "${it.name} — ${it.username}@${it.host}:${it.port}" } ?: ""
        return component
    }
}

private class ServerProfileDialog(project: Project, existing: ServerProfile?) : DialogWrapper(project, true) {
    private val profileId = existing?.id ?: UUID.randomUUID().toString()
    private val nameField = JBTextField(existing?.name.orEmpty())
    private val hostField = JBTextField(existing?.host.orEmpty())
    private val portField = JSpinner(SpinnerNumberModel(existing?.port ?: 22, 1, 65535, 1))
    private val usernameField = JBTextField(existing?.username.orEmpty())
    private val passwordField = JBPasswordField().apply {
        text = existing?.let { PasswordStore.get(it.id) }.orEmpty()
    }
    private val mappingsModel = object : DefaultTableModel(arrayOf("Project-relative path", "Remote directory"), 0) {
        override fun isCellEditable(row: Int, column: Int) = true
    }
    private val mappingsTable = JTable(mappingsModel)

    init {
        title = if (existing == null) MyMessageBundle.message("server.dialog.add") else MyMessageBundle.message("server.dialog.edit")
        existing?.mappings?.forEach { mappingsModel.addRow(arrayOf(it.localPath, it.remotePath)) }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mappingPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(mappingsTable), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton(MyMessageBundle.message("mapping.add")).apply {
                    addActionListener { mappingsModel.addRow(arrayOf("", "")) }
                })
                add(JButton(MyMessageBundle.message("mapping.remove")).apply {
                    addActionListener {
                        mappingsTable.selectedRows.sortedDescending().forEach(mappingsModel::removeRow)
                    }
                })
            }, BorderLayout.SOUTH)
        }
        mappingPanel.preferredSize = java.awt.Dimension(680, 220)

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(MyMessageBundle.message("server.name"), nameField)
            .addLabeledComponent(MyMessageBundle.message("server.host"), hostField)
            .addLabeledComponent(MyMessageBundle.message("server.port"), portField)
            .addLabeledComponent(MyMessageBundle.message("server.username"), usernameField)
            .addLabeledComponent(MyMessageBundle.message("server.password"), passwordField)
            .addSeparator()
            .addLabeledComponentFillVertically(MyMessageBundle.message("server.mappings"), mappingPanel)
            .panel
    }

    override fun doValidate(): ValidationInfo? {
        stopEditing()
        if (nameField.text.isBlank()) return ValidationInfo(MyMessageBundle.message("validation.required"), nameField)
        if (hostField.text.isBlank()) return ValidationInfo(MyMessageBundle.message("validation.required"), hostField)
        if (usernameField.text.isBlank()) return ValidationInfo(MyMessageBundle.message("validation.required"), usernameField)
        if (passwordField.password.isEmpty()) return ValidationInfo(MyMessageBundle.message("validation.required"), passwordField)
        if (mappingsModel.rowCount == 0) return ValidationInfo(MyMessageBundle.message("validation.mapping.required"), mappingsTable)

        val localPaths = mutableSetOf<String>()
        mappings().forEach { mapping ->
            val local = PathMappingResolver.normalizeLocal(mapping.localPath)
            if (local.split('/').any { it == ".." }) {
                return ValidationInfo(MyMessageBundle.message("validation.local.path"), mappingsTable)
            }
            if (!localPaths.add(local)) {
                return ValidationInfo(MyMessageBundle.message("validation.mapping.duplicate", local), mappingsTable)
            }
            try {
                if (!PathMappingResolver.normalizeRemote(mapping.remotePath).startsWith("/")) {
                    return ValidationInfo(MyMessageBundle.message("validation.remote.absolute"), mappingsTable)
                }
            } catch (_: IllegalArgumentException) {
                return ValidationInfo(MyMessageBundle.message("validation.remote.path"), mappingsTable)
            }
        }
        return null
    }

    fun profile(): ServerProfile {
        stopEditing()
        return ServerProfile(
            id = profileId,
            name = nameField.text.trim(),
            host = hostField.text.trim(),
            port = portField.value as Int,
            username = usernameField.text.trim(),
            mappings = mappings().toMutableList(),
        )
    }

    fun password(): String = passwordField.password.concatToString()

    private fun mappings(): List<PathMapping> =
        (0 until mappingsModel.rowCount).map { row ->
            PathMapping(
                localPath = PathMappingResolver.normalizeLocal(mappingsModel.getValueAt(row, 0)?.toString().orEmpty()),
                remotePath = mappingsModel.getValueAt(row, 1)?.toString().orEmpty().trim(),
            )
        }

    private fun stopEditing() {
        if (mappingsTable.isEditing) mappingsTable.cellEditor.stopCellEditing()
    }
}
