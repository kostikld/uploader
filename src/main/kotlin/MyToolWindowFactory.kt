package org.kavo.uploader

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.PopupHandler
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import org.kavo.uploader.settings.PasswordStore
import org.kavo.uploader.settings.PathMapping
import org.kavo.uploader.settings.ServerAction
import org.kavo.uploader.settings.ServerActionValidationError
import org.kavo.uploader.settings.ServerProfile
import org.kavo.uploader.settings.SftpSettings
import org.kavo.uploader.settings.validateServerActions
import org.kavo.uploader.upload.PasswordAuthentication
import org.kavo.uploader.upload.PathMappingResolver
import org.kavo.uploader.upload.SftpUploadService
import org.kavo.uploader.upload.formatElapsedTime
import org.kavo.uploader.upload.isSuccessfulCommandExit
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Point
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.util.UUID
import javax.swing.AbstractAction
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.KeyStroke
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
        addMouseListener(object : PopupHandler() {
            override fun invokePopup(component: Component, x: Int, y: Int) {
                showContextMenu(x, y)
            }
        })
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
                        AppExecutorUtil.getAppExecutorService().execute {
                            PasswordStore.remove(it.id)
                        }
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

    private fun showContextMenu(x: Int, y: Int) {
        val point = Point(x, y)
        val index = serverList.locationToIndex(point)
        if (index < 0 || !serverList.getCellBounds(index, index).contains(point)) return

        serverList.selectedIndex = index
        val profile = serverList.selectedValue ?: return
        JPopupMenu().apply {
            add(JMenu(MyMessageBundle.message("server.context.actions")).apply {
                profile.actions.forEach { action ->
                    add(JMenuItem(action.name).apply {
                        addActionListener { executeAction(profile, action) }
                    })
                }
            })
            show(serverList, x, y)
        }
    }

    private fun executeAction(profile: ServerProfile, action: ServerAction) {
        object : Task.Backgroundable(
            project,
            MyMessageBundle.message("action.execution.progress", action.name, profile.name, "00:00"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                val startedAt = System.nanoTime()
                fun updateProgress() {
                    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                    indicator.text = MyMessageBundle.message(
                        "action.execution.progress",
                        action.name,
                        profile.name,
                        formatElapsedTime(elapsedMillis),
                    )
                }

                try {
                    val password = PasswordStore.get(profile.id)?.toByteArray()
                    if (password == null) {
                        UploaderNotifications.error(
                            project,
                            MyMessageBundle.message("error.password.missing", profile.name),
                        )
                        return
                    }
                    updateProgress()
                    val result = ApplicationManager.getApplication().getService(SftpUploadService::class.java)
                        .executeCommand(profile, PasswordAuthentication(password), action.command) {
                            indicator.checkCanceled()
                            updateProgress()
                        }
                    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                    val status = if (isSuccessfulCommandExit(result.exitStatus)) {
                        MyMessageBundle.message("action.execution.success", action.name, profile.name)
                    } else {
                        MyMessageBundle.message(
                            "action.execution.exit.failed",
                            action.name,
                            profile.name,
                            result.exitStatus,
                        )
                    }
                    val notification = formatActionExecutionNotification(
                        exitStatus = result.exitStatus,
                        status = status,
                        standardOutput = result.standardOutput,
                        isOutputTruncated = result.isOutputTruncated,
                        truncationMarker = MyMessageBundle.message("action.execution.output.truncated"),
                        completion = MyMessageBundle.message(
                            "action.execution.completed",
                            formatElapsedTime(elapsedMillis),
                        ),
                    )
                    if (notification.isInformational) {
                        UploaderNotifications.info(
                            project,
                            notification.content,
                        )
                    } else {
                        UploaderNotifications.error(
                            project,
                            notification.content,
                        )
                    }
                } catch (_: ProcessCanceledException) {
                    UploaderNotifications.error(
                        project,
                        MyMessageBundle.message("action.execution.cancelled", action.name, profile.name),
                    )
                } catch (error: Exception) {
                    UploaderNotifications.error(
                        project,
                        MyMessageBundle.message(
                            "action.execution.failed",
                            action.name,
                            profile.name,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            }
        }.queue()
    }

    private fun editProfile(existing: ServerProfile?) {
        val dialog = ServerProfileDialog(project, existing)
        if (!dialog.showAndGet()) return
        val profile = dialog.profile()
        val password = dialog.password()
        settings.save(profile)
        refresh()
        if (password != null) {
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    PasswordStore.set(profile.id, profile.username, password)
                } catch (error: Exception) {
                    UploaderNotifications.error(
                        project,
                        MyMessageBundle.message(
                            "password.save.failed",
                            profile.name,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }

    private fun testConnection(profile: ServerProfile) {
        object : Task.Backgroundable(project, MyMessageBundle.message("connection.testing", profile.name), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val password = PasswordStore.get(profile.id)?.toByteArray()
                    if (password == null) {
                        UploaderNotifications.error(
                            project,
                            MyMessageBundle.message("error.password.missing", profile.name),
                        )
                        return
                    }
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
    private val isNewProfile = existing == null
    private val profileId = existing?.id ?: UUID.randomUUID().toString()
    private val nameField = JBTextField(existing?.name.orEmpty())
    private val hostField = JBTextField(existing?.host.orEmpty())
    private val portField = JSpinner(SpinnerNumberModel(existing?.port ?: 22, 1, 65535, 1))
    private val usernameField = JBTextField(existing?.username.orEmpty())
    private val passwordField = JBPasswordField()
    private val mappingsModel = object : DefaultTableModel(arrayOf("Project-relative path", "Remote directory"), 0) {
        override fun isCellEditable(row: Int, column: Int) = true
    }
    private val mappingsTable = JTable(mappingsModel).apply {
        installCellClipboardActions(this)
    }
    private val actionsModel = object : DefaultTableModel(
        arrayOf(
            MyMessageBundle.message("server.action.name"),
            MyMessageBundle.message("server.action.command"),
            "id",
        ),
        0,
    ) {
        override fun isCellEditable(row: Int, column: Int) = column < 2
    }
    private val actionsTable = JTable(actionsModel).apply {
        columnModel.removeColumn(columnModel.getColumn(2))
        installCellClipboardActions(this)
    }

    init {
        title = if (existing == null) MyMessageBundle.message("server.dialog.add") else MyMessageBundle.message("server.dialog.edit")
        if (existing == null) {
            mappingsModel.addRow(arrayOf("", ""))
        } else {
            existing.mappings.forEach { mappingsModel.addRow(arrayOf(it.localPath, it.remotePath)) }
            existing.actions.forEach { actionsModel.addRow(arrayOf(it.name, it.command, it.id)) }
        }
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
        val actionsPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(actionsTable), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton(MyMessageBundle.message("server.action.add")).apply {
                    addActionListener { actionsModel.addRow(arrayOf("", "", UUID.randomUUID().toString())) }
                })
                add(JButton(MyMessageBundle.message("server.action.remove")).apply {
                    addActionListener {
                        actionsTable.selectedRows.sortedDescending().forEach(actionsModel::removeRow)
                    }
                })
            }, BorderLayout.SOUTH)
        }
        actionsPanel.preferredSize = java.awt.Dimension(680, 160)

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(MyMessageBundle.message("server.name"), nameField)
            .addLabeledComponent(MyMessageBundle.message("server.host"), hostField)
            .addLabeledComponent(MyMessageBundle.message("server.port"), portField)
            .addLabeledComponent(MyMessageBundle.message("server.username"), usernameField)
            .addLabeledComponent(
                MyMessageBundle.message(if (isNewProfile) "server.password" else "server.password.unchanged"),
                passwordField,
            )
            .addSeparator()
            .addLabeledComponentFillVertically(MyMessageBundle.message("server.mappings"), mappingPanel)
            .addLabeledComponentFillVertically(MyMessageBundle.message("server.actions"), actionsPanel)
            .panel
    }

    override fun doOKAction() {
        stopEditing()
        val validation = validateProfile()
        if (validation != null) {
            setErrorText(validation.message)
            validation.component?.requestFocusInWindow()
            return
        }
        setErrorText(null)
        super.doOKAction()
    }

    override fun doValidate(): ValidationInfo? = null

    private fun validateProfile(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo(MyMessageBundle.message("validation.required"), nameField)
        if (hostField.text.isBlank()) return ValidationInfo(MyMessageBundle.message("validation.required"), hostField)
        if (usernameField.text.isBlank()) return ValidationInfo(MyMessageBundle.message("validation.required"), usernameField)
        if (isNewProfile && passwordField.password.isEmpty()) {
            return ValidationInfo(MyMessageBundle.message("validation.required"), passwordField)
        }

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
        when (validateServerActions(actions())) {
            ServerActionValidationError.BLANK_NAME ->
                return ValidationInfo(MyMessageBundle.message("validation.action.name.required"), actionsTable)
            ServerActionValidationError.BLANK_COMMAND ->
                return ValidationInfo(MyMessageBundle.message("validation.action.command.required"), actionsTable)
            ServerActionValidationError.DUPLICATE_NAME ->
                return ValidationInfo(MyMessageBundle.message("validation.action.name.duplicate"), actionsTable)
            null -> Unit
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
            actions = actions().toMutableList(),
        )
    }

    fun password(): String? = passwordField.password.concatToString().takeIf { it.isNotEmpty() }

    private fun mappings(): List<PathMapping> =
        (0 until mappingsModel.rowCount).map { row ->
            PathMapping(
                localPath = PathMappingResolver.normalizeLocal(mappingsModel.getValueAt(row, 0)?.toString().orEmpty()),
                remotePath = mappingsModel.getValueAt(row, 1)?.toString().orEmpty().trim(),
            )
        }.filterNot { it.localPath.isBlank() && it.remotePath.isBlank() }

    private fun actions(): List<ServerAction> =
        (0 until actionsModel.rowCount).map { row ->
            ServerAction(
                id = actionsModel.getValueAt(row, 2)?.toString().orEmpty(),
                name = actionsModel.getValueAt(row, 0)?.toString().orEmpty().trim(),
                command = actionsModel.getValueAt(row, 1)?.toString().orEmpty().trim(),
            )
        }

    private fun stopEditing() {
        if (mappingsTable.isEditing) mappingsTable.cellEditor.stopCellEditing()
        if (actionsTable.isEditing) actionsTable.cellEditor.stopCellEditing()
    }
}

private fun installCellClipboardActions(table: JTable) {
    val shortcutMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
    val inputMap = table.getInputMap(JComponent.WHEN_FOCUSED)
    val actionMap = table.actionMap

    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcutMask), "copySelectedCell")
    actionMap.put("copySelectedCell", object : AbstractAction() {
        override fun actionPerformed(event: ActionEvent) {
            val row = table.selectedRow
            val column = table.selectedColumn
            if (row < 0 || column < 0) return
            val value = table.getValueAt(row, column)?.toString().orEmpty()
            CopyPasteManager.getInstance().setContents(StringSelection(value))
        }
    })

    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcutMask), "pasteSelectedCell")
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
}
