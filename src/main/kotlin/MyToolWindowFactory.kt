package org.kavo.uploader

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.PopupHandler
import com.intellij.ui.MouseDragHelper
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import org.kavo.uploader.actions.launchUploadChangedFiles
import org.kavo.uploader.settings.*
import org.kavo.uploader.transfer.*
import org.kavo.uploader.upload.*
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.util.*
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance().createContent(ServerProfilesPanel(project), null, false)
        toolWindow.contentManager.addContent(content)
    }
}

private class ServerProfilesPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val settings = SftpSettings.getInstance()
    private val treeModel = DefaultTreeModel(ServerNode(MyMessageBundle.message("folder.root")))
    private val serverTree = JTree(treeModel).apply {
        isRootVisible = true
        setShowsRootHandles(true)
        setEditable(false)
        cellRenderer = ServerNodeRenderer()
        addMouseListener(object : PopupHandler() {
            override fun invokePopup(component: Component, x: Int, y: Int) {
                showContextMenu(x, y)
            }
        })
    }

    init {
        add(JBScrollPane(serverTree), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton(MyMessageBundle.message("server.add")).apply {
                addActionListener { editProfile(null) }
             })
            add(JButton(MyMessageBundle.message("folder.add")).apply {
                addActionListener { addFolder() }
             })
            add(JButton(MyMessageBundle.message("server.upload.git_changed")).apply {
                addActionListener { launchUploadChangedFiles(project) }
              })
         }, BorderLayout.SOUTH)
        serverTree.addMouseListener(DragDropTracker(serverTree, this, project))
        refresh()
    }

    internal fun refresh() {
        val selectedKey = selectionKey(serverTree.selectionPath?.lastPathComponent as? ServerNode)
        val root = ServerNode(MyMessageBundle.message("folder.root"), "")
        settings.folders().forEach { path ->
            var parent = root
            path.split('/').forEach { segment ->
                val node = parent.folderChildren.firstOrNull { it.name == segment }
                    ?: ServerNode(segment, parent.path.let { if (it.isEmpty()) segment else "$it/$segment" })
                        .also { parent.folderChildren.add(it) }
                parent = node
            }
        }
        settings.servers().forEach { profile ->
            val segments = pathSegments(profile.folder)
            if (segments.isEmpty()) {
                root.serverChildren.add(ServerNode(profile.name, "", profile))
            } else {
            var parent = root
            segments.forEach { segment ->
                val node = parent.folderChildren.firstOrNull { it.name == segment }
                    ?: ServerNode(segment, parent.path.let { if (it.isEmpty()) segment else "$it/$segment" })
                        .also { parent.folderChildren.add(it) }
                parent = node
            }
            parent.serverChildren.add(ServerNode(profile.name, parent.path, profile))
            }
        }
        treeModel.setRoot(root)
        treeModel.reload()
        selectedKey?.let { key ->
            locate(root, key)?.let { serverTree.setSelectionPath(TreePath(it)) }
        }
    }

    private fun selectionKey(node: ServerNode?): String? =
        when {
            node == null || node.name == MyMessageBundle.message("folder.root") -> null
            node.profile == null -> node.path
            else -> node.profile!!.id
        }

    private fun locate(current: ServerNode, key: String): List<ServerNode>? {
        val match = current.profile?.id == key ||
            (current.profile == null && key == current.path)
        if (match) return listOf(current)
        (current.folderChildren + current.serverChildren).forEach { child ->
            locate(child, key)?.let { return listOf(current) + it }
        }
        return null
    }

    private fun addFolder() {
        val name = Messages.showInputDialog(
            this,
            MyMessageBundle.message("folder.add.prompt"),
            MyMessageBundle.message("folder.add"),
            Messages.getQuestionIcon(),
        )?.trim()
        if (name.isNullOrBlank()) return
        if (settings.addFolder(name)) {
            refresh()
        } else {
            UploaderNotifications.info(project, MyMessageBundle.message("folder.exists", name))
        }
    }

    private fun showContextMenu(x: Int, y: Int) {
        val path = serverTree.getPathForLocation(x, y) ?: return
        val node = path.lastPathComponent as? ServerNode ?: return
        serverTree.setSelectionRow(serverTree.getRowForPath(path))
        if (node.profile == null && node.path.isNotEmpty()) {
            showFolderMenu(node, x, y)
        } else {
            showServerMenu(node.profile, x, y)
        }
    }

    private fun showServerMenu(profile: ServerProfile?, x: Int, y: Int) {
        if (profile == null) return
        JPopupMenu().apply {
            add(JMenuItem(MyMessageBundle.message("server.edit")).apply {
                addActionListener { editProfile(profile) }
             })
            add(JMenuItem(MyMessageBundle.message("server.test")).apply {
                addActionListener { testConnection(profile) }
             })
            add(JMenuItem(MyMessageBundle.message("server.remove")).apply {
                addActionListener { removeProfile(profile) }
             })
            addSeparator()
            add(JMenu(MyMessageBundle.message("server.move")).apply {
                add(JMenuItem(MyMessageBundle.message("folder.ungrouped")).apply {
                    addActionListener { moveProfileToFolder(profile, "") }
                 })
                settings.folders().forEach { folder ->
                    add(JMenuItem(folder).apply {
                        addActionListener { moveProfileToFolder(profile, folder) }
                     })
                }
             })
            addSeparator()
            add(JMenu(MyMessageBundle.message("server.context.actions")).apply {
                profile.actions.forEach { action ->
                    add(JMenuItem(action.name).apply {
                        addActionListener { executeAction(profile, action) }
                     })
                }
            })
            show(serverTree, x, y)
          }
    }

    private fun showFolderMenu(node: ServerNode, x: Int, y: Int) {
        val folder = node.path
        JPopupMenu().apply {
            add(JMenuItem(MyMessageBundle.message("folder.rename")).apply {
                addActionListener { renameFolder(folder) }
             })
            add(JMenuItem(MyMessageBundle.message("folder.remove")).apply {
                addActionListener { deleteFolder(folder) }
             })
            show(serverTree, x, y)
          }
    }

    private fun renameFolder(folder: String) {
        val name = Messages.showInputDialog(
            this,
            MyMessageBundle.message("folder.rename.prompt", folder),
            MyMessageBundle.message("folder.rename"),
            Messages.getQuestionIcon(),
        )?.trim()
        if (name.isNullOrBlank() || name == folder) return
        if (settings.folders().contains(name)) {
            UploaderNotifications.info(project, MyMessageBundle.message("folder.exists", name))
            return
        }
        settings.renameFolder(folder, name)
        refresh()
    }

    internal fun moveProfileToFolder(profile: ServerProfile, folder: String) {
        profile.folder = folder
        settings.save(profile)
        refresh()
    }

    private fun deleteFolder(folder: String) {
        settings.deleteFolder(folder)
        refresh()
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

    private fun removeProfile(profile: ServerProfile) {
        settings.remove(profile.id)
        refresh()
        AppExecutorUtil.getAppExecutorService().execute {
            PasswordStore.remove(profile.id)
          }
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
                        MyMessageBundle.message(
                            "connection.failed",
                            profile.name,
                            error.message ?: error.javaClass.simpleName
                        ),
                    )
                }
            }
        }.queue()
    }
}

private class DragDropTracker(
    private val tree: JTree,
    private val panel: ServerProfilesPanel,
    private val owner: Project,
) : MouseDragHelper<JTree>(owner, tree) {
    private var dragged: ServerProfile? = null

    override fun canStartDragging(component: javax.swing.JComponent, pressedOnScreenPoint: java.awt.Point): Boolean =
        dragged != null

    override fun processMousePressed(event: java.awt.event.MouseEvent) {
        dragged = if (event.button == 1) nodeAt(event.point)?.profile else null
    }

    override fun canFinishDragging(event: java.awt.event.MouseEvent): Boolean {
        if (dragged == null) return false
        val target = nodeAt(event.point)
        return target != null && target.profile == null
    }

    override fun processDragFinish(event: java.awt.event.MouseEvent, pressedOnScreen: Boolean) {
        val profile = dragged
        dragged = null
        if (profile != null) {
            nodeAt(event.point)?.let { target ->
                if (target.profile == null) {
                    panel.moveProfileToFolder(profile, target.path)
                }
            }
        }
    }

    override fun processDrag(event: java.awt.event.MouseEvent, pressedOnScreenPoint: java.awt.Point, currentOnScreenPoint: java.awt.Point) {}

    private fun nodeAt(point: java.awt.Point): ServerNode? =
        tree.getPathForLocation(point.x, point.y)?.lastPathComponent as? ServerNode
}

private class ServerNode(
    val name: String,
    val path: String = name,
    val profile: ServerProfile? = null,
) : DefaultMutableTreeNode() {
    val folderChildren: MutableList<ServerNode> = mutableListOf()
    val serverChildren: MutableList<ServerNode> = mutableListOf()
    private val childOrder: List<ServerNode>
        get() = folderChildren + serverChildren

    override fun getUserObject(): Any = this
    override fun isLeaf(): Boolean = childOrder.isEmpty()
    override fun getChildCount(): Int = childOrder.size
    override fun getChildAt(index: Int): TreeNode = childOrder[index]

    val displayName: String
        get() = if (profile != null) "${profile.name} — ${profile.username}@${profile.host}:${profile.port}" else name
}

private class ServerNodeRenderer : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(
        tree: JTree?,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        val component = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus) as JLabel
        val node = value as? ServerNode
        component.text = node?.displayName.orEmpty()
        component.icon = when {
            node == null || node.profile != null -> this.leafIcon
            else -> this.openIcon
        }
        return component
    }
}

private class ServerProfileDialog(private val project: Project, existing: ServerProfile?) : DialogWrapper(project, true) {
    private val isNewProfile = existing == null
    private val profileId = existing?.id ?: UUID.randomUUID().toString()
    private val nameField = JBTextField(existing?.name.orEmpty())
    private val folderField = JBTextField(existing?.folder.orEmpty())
    private val hostField = JBTextField(existing?.host.orEmpty())
    private val portField = JSpinner(SpinnerNumberModel(existing?.port ?: 22, 1, 65535, 1))
    private val usernameField = JBTextField(existing?.username.orEmpty())
    private val passwordField = JBPasswordField()
    private val useRsyncCheckbox = JCheckBox(MyMessageBundle.message("server.useRsync")).apply {
        isSelected = existing?.useRsync ?: SftpSettings.getInstance().newServerUseRsync
       }
    private val mappingsModel = object : DefaultTableModel(arrayOf("Project-relative path", "Remote directory"), 0) {
        override fun isCellEditable(row: Int, column: Int) = true
    }
    private val mappingsTable = JBTable(mappingsModel).apply {
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
    private val actionsTable = JBTable(actionsModel).apply {
        columnModel.removeColumn(columnModel.getColumn(2))
        installCellClipboardActions(this)
    }

    init {
        title =
            if (existing == null) MyMessageBundle.message("server.dialog.add") else MyMessageBundle.message("server.dialog.edit")
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
                add(JButton(MyMessageBundle.message("mapping.copy")).apply {
                    addActionListener {
                        stopEditing()
                        val mappings = (0 until mappingsModel.rowCount).map { row ->
                            PathMapping(
                                localPath = mappingsModel.getValueAt(row, 0)?.toString().orEmpty().trim(),
                                remotePath = mappingsModel.getValueAt(row, 1)?.toString().orEmpty().trim(),
                             )
                          }.filterNot { it.localPath.isBlank() && it.remotePath.isBlank() }
                        val text = ProfileTransfer.encodeMappings(mappings)
                        copyToClipboard(text)
                        UploaderNotifications.info(project, MyMessageBundle.message("mapping.copied", mappings.size))
                     }
                  })
                add(JButton(MyMessageBundle.message("mapping.import")).apply {
                    addActionListener { importMappings() }
                  })
             }, BorderLayout.SOUTH)
         }
        mappingPanel.preferredSize = Dimension(680, 220)
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
                add(JButton(MyMessageBundle.message("server.action.copy")).apply {
                    addActionListener {
                        stopEditing()
                        val actions = (0 until actionsModel.rowCount).map { row ->
                            ServerAction(
                                id = actionsModel.getValueAt(row, 2)?.toString().orEmpty(),
                                name = actionsModel.getValueAt(row, 0)?.toString().orEmpty().trim(),
                                command = actionsModel.getValueAt(row, 1)?.toString().orEmpty().trim(),
                              )
                           }.filterNot { it.name.isBlank() && it.command.isBlank() }
                        val text = ProfileTransfer.encodeActions(actions)
                        copyToClipboard(text)
                        UploaderNotifications.info(project, MyMessageBundle.message("server.action.copied", actions.size))
                      }
                   })
                add(JButton(MyMessageBundle.message("server.action.import")).apply {
                    addActionListener { importActions() }
                  })
              }, BorderLayout.SOUTH)
          }
        actionsPanel.preferredSize = Dimension(680, 160)

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(MyMessageBundle.message("server.name"), nameField)
            .addLabeledComponent(MyMessageBundle.message("server.folder"), folderField)
            .addLabeledComponent(MyMessageBundle.message("server.host"), hostField)
            .addLabeledComponent(MyMessageBundle.message("server.port"), portField)
            .addLabeledComponent(MyMessageBundle.message("server.username"), usernameField)
             .addLabeledComponent(
                 MyMessageBundle.message(if (isNewProfile) "server.password" else "server.password.unchanged"),
                 passwordField,
              )
             .addComponent(useRsyncCheckbox)
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
        val folder = folderField.text.trim()
        if (folder.isNotEmpty() && folder.split('/').any { it == ".." }) {
            return ValidationInfo(MyMessageBundle.message("validation.folder.path"), folderField)
        }
        if (hostField.text.isBlank()) return ValidationInfo(MyMessageBundle.message("validation.required"), hostField)
        if (usernameField.text.isBlank()) return ValidationInfo(
            MyMessageBundle.message("validation.required"),
            usernameField
        )
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
            folder = folderField.text.trim(),
            host = hostField.text.trim(),
            port = portField.value as Int,
            username = usernameField.text.trim(),
            useRsync = useRsyncCheckbox.isSelected,
            mappings = mappings().toMutableList(),
            actions = actions().toMutableList(),
        )
    }

    fun password(): String? = passwordField.password.concatToString().takeIf { it.isNotEmpty() }

    private fun copyToClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
      }

    private fun importMappings() {
        val dialog = ImportLinesDialog(project, MyMessageBundle.message("mapping.import"), MyMessageBundle.message("import.mappings.hint"))
        if (!dialog.showAndGet()) return
        val mappings = ProfileTransfer.decodeMappings(dialog.text())
        mappings.forEach { mappingsModel.addRow(arrayOf(it.localPath, it.remotePath)) }
        UploaderNotifications.info(project, MyMessageBundle.message("mapping.imported", mappings.size))
      }

    private fun importActions() {
        val dialog = ImportLinesDialog(project, MyMessageBundle.message("server.action.import"), MyMessageBundle.message("import.actions.hint"))
        if (!dialog.showAndGet()) return
        val actions = ProfileTransfer.decodeActions(dialog.text())
        actions.forEach { actionsModel.addRow(arrayOf(it.name, it.command, it.id)) }
        UploaderNotifications.info(project, MyMessageBundle.message("server.action.imported", actions.size))
      }

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

private class ImportLinesDialog(
    project: Project,
    dialogTitle: String,
    private val hint: String,
) : DialogWrapper(project, true) {
    private val textArea = JBTextArea(10, 50).apply {
        lineWrap = false
        }

    init {
        title = dialogTitle
        init()
        }

    fun text(): String = textArea.text

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(JLabel(hint), BorderLayout.NORTH)
        panel.add(JBScrollPane(textArea), BorderLayout.CENTER)
        return panel
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
