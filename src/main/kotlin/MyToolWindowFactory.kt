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
import com.intellij.ui.JBColor
import com.intellij.ui.MouseDragHelper
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import org.kavo.uploader.actions.launchUploadChangedFiles
import org.kavo.uploader.settings.*
import org.kavo.uploader.transfer.ProfileTransfer
import org.kavo.uploader.ui.installCloneShortcut
import org.kavo.uploader.ui.installTableShortcuts
import org.kavo.uploader.upload.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.util.*
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.tree.*

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance().createContent(ServerProfilesPanel(project), null, false)
        toolWindow.contentManager.addContent(content)
    }
}

private val serverPanelBackground
    get() = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())

private fun copyToClipboard(text: String) {
    CopyPasteManager.getInstance().setContents(StringSelection(text))
}

private const val TREE_KEY = "tree"
private const val SERVERS_KEY = "servers"
private const val CREDENTIALS_KEY = "credentials"

// Shown instead of a stored password: the secret itself is never read into the server dialog.
private const val PASSWORD_PLACEHOLDER_LENGTH = 8

private fun folderKey(path: String) = "folder:$path"
private fun serverKey(profileId: String) = "server:$profileId"
private fun credentialKey(credentialsId: String) = "credential:$credentialsId"

/** A row of the tool window tree: the hidden wrapper, one of the two top-level folders, or an entry. */
private enum class NodeKind { TREE, SERVERS, FOLDER, SERVER, CREDENTIALS, CREDENTIAL }

private class ServerProfilesPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val treeModel = DefaultTreeModel(ServerNode(MyMessageBundle.message("folder.root"), kind = NodeKind.SERVERS))
    private val serverTree = JTree(treeModel).apply {
        isRootVisible = false
        setShowsRootHandles(true)
        setEditable(false)
        background = serverPanelBackground
        cellRenderer = ServerNodeRenderer()
        addMouseListener(object : PopupHandler() {
            override fun invokePopup(component: Component, x: Int, y: Int) {
                showContextMenu(x, y)
            }
        })
        addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(event: java.awt.event.KeyEvent) {
                if (event.keyCode == java.awt.event.KeyEvent.VK_DELETE || event.keyCode == java.awt.event.KeyEvent.VK_BACK_SPACE) {
                    deleteSelected()
                }
            }
        })
        installCloneShortcut(this) { cloneSelected() }
    }

    private var menuPanel = buildMenuPanel()

    init {
        background = serverPanelBackground
        add(JBScrollPane(serverTree).apply {
            background = serverPanelBackground
            viewport.background = serverPanelBackground
        }, BorderLayout.CENTER)
        add(menuPanel, BorderLayout.SOUTH)
        serverTree.addMouseListener(DragDropTracker(serverTree, this, project))
        refresh()
    }

    private fun buildMenuPanel(): MenuButtonsPanel = MenuButtonsPanel(SettingsAccess.wrapMenuButtons(project)).apply {
        background = serverPanelBackground
        addMenuButton(JButton(MyMessageBundle.message("server.add")).apply {
            addActionListener { editProfile(null) }
        })
        if (SettingsAccess.showMenuAddFolderButton(project)) {
            addMenuButton(JButton(MyMessageBundle.message("folder.add")).apply {
                addActionListener { addFolder() }
            })
        }
        addMenuButton(JButton(MyMessageBundle.message("server.upload.git_changed")).apply {
            addActionListener { launchUploadChangedFiles(project, selectedProfile()) }
        })
    }

    fun refresh() {
        remove(menuPanel)
        menuPanel = buildMenuPanel()
        add(menuPanel, BorderLayout.SOUTH)
        revalidate()
        val expandedFolders = expandedFolderKeys()
        val selectedKey = selectionKey(serverTree.selectionPath?.lastPathComponent as? ServerNode)
        val root = ServerNode("", "", kind = NodeKind.TREE)
        val servers = ServerNode(MyMessageBundle.message("folder.root"), "", kind = NodeKind.SERVERS)
        root.folderChildren.add(servers)
        SettingsAccess.local(project)
        SettingsAccess.folders(project).forEach { path ->
            var parent = servers
            pathSegments(path).forEach { segment ->
                val node = parent.folderChildren.firstOrNull { it.name == segment }
                    ?: ServerNode(segment, parent.path.let { if (it.isEmpty()) segment else "$it/$segment" })
                        .also { parent.folderChildren.add(it) }
                parent = node
            }
        }
        SettingsAccess.allServers(project).forEach { profile ->
            val segments = pathSegments(profile.folder)
            if (segments.isEmpty()) {
                servers.serverChildren.add(ServerNode(profile.name, "", profile, kind = NodeKind.SERVER))
            } else {
                var parent = servers
                segments.forEach { segment ->
                    val node = parent.folderChildren.firstOrNull { it.name == segment }
                        ?: ServerNode(segment, parent.path.let { if (it.isEmpty()) segment else "$it/$segment" })
                            .also { parent.folderChildren.add(it) }
                    parent = node
                }
                parent.serverChildren.add(ServerNode(profile.name, parent.path, profile, kind = NodeKind.SERVER))
            }
        }
        root.folderChildren.add(credentialsFolder())
        treeModel.setRoot(root)
        treeModel.reload()
        expandedFolders.forEach { key ->
            locate(root, key)?.let { serverTree.expandPath(treePath(it)) }
        }
        selectedKey?.let { key ->
            locate(root, key)?.let { serverTree.setSelectionPath(treePath(it)) }
        }
    }

    /** The credentials folder next to **Servers**: one row per entry, edited through its own dialog. */
    private fun credentialsFolder(): ServerNode =
        ServerNode(MyMessageBundle.message("credentials.root"), "", kind = NodeKind.CREDENTIALS).apply {
            SettingsAccess.credentials(project).forEach { entry ->
                serverChildren.add(ServerNode(entry.name, "", credential = entry, kind = NodeKind.CREDENTIAL))
            }
        }

    private fun deleteSelected() {
        val node = serverTree.selectionPath?.lastPathComponent as? ServerNode ?: return
        when (node.kind) {
            NodeKind.SERVER -> {
                val profile = node.profile ?: return
                if (profile.type == ServerType.LOCAL) {
                    UploaderNotifications.error(
                        project,
                        MyMessageBundle.message("server.local.delete.unsupported", profile.name),
                    )
                } else {
                    removeProfile(profile)
                }
            }

            NodeKind.CREDENTIAL -> node.credential?.let { removeCredential(it) }
            NodeKind.FOLDER -> {
                if (node.path.isEmpty()) return
                if (!FolderDeleteDialog(project, node.path).showAndGet()) return
                deleteFolder(node.path)
            }

            NodeKind.TREE, NodeKind.SERVERS, NodeKind.CREDENTIALS -> Unit
        }
    }

    private fun cloneSelected() {
        val profile = (serverTree.selectionPath?.lastPathComponent as? ServerNode)?.profile ?: return
        if (profile.type == ServerType.LOCAL) {
            UploaderNotifications.error(
                project,
                MyMessageBundle.message("server.local.clone.unsupported", profile.name),
            )
            return
        }
        val clone = cloneProfile(profile, SettingsAccess.allServers(project).map { it.name })
        SettingsAccess.save(project, clone)
        refresh()
        selectNode(serverKey(clone.id))
        UploaderNotifications.info(
            project,
            MyMessageBundle.message("server.cloned", profile.name, clone.name),
        )
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                PasswordStore.storeCloneSecret(profile, clone)
            } catch (error: Exception) {
                UploaderNotifications.error(
                    project,
                    MyMessageBundle.message(
                        "password.save.failed",
                        clone.name,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    private fun selectNode(key: String) {
        val root = treeModel.root as? ServerNode ?: return
        val nodes = locate(root, key) ?: return
        // Opening every ancestor first: a cloned server or an edited entry inside a collapsed folder
        // would otherwise be selected out of sight.
        nodes.indices.forEach { index -> serverTree.expandPath(treePath(nodes.take(index + 1))) }
        serverTree.setSelectionPath(treePath(nodes))
    }

    private fun selectedProfile(): ServerProfile? =
        (serverTree.selectionPath?.lastPathComponent as? ServerNode)?.profile

    private fun selectionKey(node: ServerNode?): String? =
        when {
            node == null || node.kind == NodeKind.TREE -> null
            else -> nodeKey(node)
        }

    /** Identifies a node across a refresh, when every node object is replaced by a new one. */
    private fun nodeKey(node: ServerNode): String =
        when (node.kind) {
            NodeKind.SERVER -> serverKey(node.profile?.id.orEmpty())
            NodeKind.CREDENTIAL -> credentialKey(node.credential?.id.orEmpty())
            NodeKind.FOLDER -> folderKey(node.path)
            NodeKind.SERVERS -> SERVERS_KEY
            NodeKind.CREDENTIALS -> CREDENTIALS_KEY
            NodeKind.TREE -> TREE_KEY
        }

    private fun locate(current: ServerNode, key: String): List<ServerNode>? {
        if (nodeKey(current) == key) return listOf(current)
        (current.folderChildren + current.serverChildren).forEach { child ->
            locate(child, key)?.let { return listOf(current) + it }
        }
        return null
    }

    private fun treePath(nodes: List<ServerNode>): TreePath =
        nodes.drop(1).fold(TreePath(nodes.first())) { path, node -> path.pathByAddingChild(node) }

    // A refresh replaces every node, and Swing then forgets which folders were open. Remembering them
    // keeps a folder open when a server inside it is added, cloned or removed.
    private fun expandedFolderKeys(): List<String> {
        val root = treeModel.root as? ServerNode ?: return emptyList()
        return collectExpandedFolders(root, treePath(listOf(root)))
    }

    private fun collectExpandedFolders(node: ServerNode, path: TreePath): List<String> {
        val keys = mutableListOf<String>()
        node.folderChildren.forEach { child ->
            val childPath = path.pathByAddingChild(child)
            if (serverTree.isExpanded(childPath)) {
                keys.add(nodeKey(child))
                keys.addAll(collectExpandedFolders(child, childPath))
            }
        }
        return keys
    }

    private fun addFolder(parent: String = "") {
        val prompt =
            if (parent.isEmpty()) MyMessageBundle.message("folder.add.prompt")
            else MyMessageBundle.message("folder.add.sub.prompt")
        val name = Messages.showInputDialog(
            this,
            prompt,
            MyMessageBundle.message("folder.add"),
            Messages.getQuestionIcon(),
        )?.trim()
        if (name.isNullOrBlank()) return
        if (pathSegments(name).any { it == ".." }) {
            UploaderNotifications.info(project, MyMessageBundle.message("validation.folder.path"))
            return
        }
        val folder = nestedFolderPath(parent, name)
        if (SettingsAccess.addFolder(project, folder)) {
            refresh()
        } else {
            UploaderNotifications.info(project, MyMessageBundle.message("folder.exists", folder))
        }
    }

    private fun showContextMenu(x: Int, y: Int) {
        val path = serverTree.getPathForLocation(x, y) ?: return
        val node = path.lastPathComponent as? ServerNode ?: return
        serverTree.setSelectionRow(serverTree.getRowForPath(path))
        when (node.kind) {
            NodeKind.FOLDER -> showFolderMenu(node, x, y)
            NodeKind.SERVER -> showServerMenu(node.profile, x, y)
            NodeKind.CREDENTIAL -> showCredentialMenu(node.credential, x, y)
            NodeKind.CREDENTIALS -> showCredentialsFolderMenu(x, y)
            NodeKind.SERVERS -> showServersFolderMenu(x, y)
            NodeKind.TREE -> Unit
        }
    }

    /** The **Servers** folder itself: a top-level folder can be created here. */
    private fun showServersFolderMenu(x: Int, y: Int) {
        JPopupMenu().apply {
            add(JMenuItem(MyMessageBundle.message("folder.add")).apply {
                addActionListener { addFolder("") }
            })
            show(serverTree, x, y)
        }
    }

    private fun showServerMenu(profile: ServerProfile?, x: Int, y: Int) {
        if (profile == null) return
        val isLocal = profile.type == ServerType.LOCAL
        JPopupMenu().apply {
            add(JMenuItem(MyMessageBundle.message("server.edit")).apply {
                addActionListener { editProfile(profile) }
            })
            if (!isLocal) {
                add(JMenuItem(MyMessageBundle.message("server.test")).apply {
                    addActionListener { testConnection(profile) }
                })
            }
            if (!isLocal) {
                add(JMenuItem(MyMessageBundle.message("server.remove")).apply {
                    addActionListener { removeProfile(profile) }
                })
            }
            if (!isLocal) {
                addSeparator()
                add(JMenu(MyMessageBundle.message("server.move")).apply {
                    add(JMenuItem(MyMessageBundle.message("folder.ungrouped")).apply {
                        addActionListener { moveProfileToFolder(profile, "") }
                    })
                    SettingsAccess.folders(project).forEach { folder ->
                        add(JMenuItem(folder).apply {
                            addActionListener { moveProfileToFolder(profile, folder) }
                        })
                    }
                })
            }
            if (!isLocal) {
                addSeparator()
                add(JMenu(MyMessageBundle.message("server.context.actions")).apply {
                    profile.actions.forEach { action ->
                        add(JMenuItem(action.name).apply {
                            addActionListener { executeAction(profile, action) }
                        })
                    }
                })
            }
            show(serverTree, x, y)
        }
    }

    private fun showFolderMenu(node: ServerNode, x: Int, y: Int) {
        val folder = node.path
        JPopupMenu().apply {
            add(JMenuItem(MyMessageBundle.message("folder.add")).apply {
                addActionListener { addFolder(folder) }
            })
            add(JMenuItem(MyMessageBundle.message("folder.rename")).apply {
                addActionListener { renameFolder(folder) }
            })
            add(JMenuItem(MyMessageBundle.message("folder.remove")).apply {
                addActionListener { deleteFolder(folder) }
            })
            show(serverTree, x, y)
        }
    }

    private fun showCredentialsFolderMenu(x: Int, y: Int) {
        JPopupMenu().apply {
            add(JMenuItem(MyMessageBundle.message("credentials.add")).apply {
                addActionListener { editCredential(null) }
            })
            add(JMenuItem(MyMessageBundle.message("credentials.copy")).apply {
                addActionListener { copyCredentials() }
            })
            add(JMenuItem(MyMessageBundle.message("credentials.import")).apply {
                addActionListener { importCredentials() }
            })
            show(serverTree, x, y)
        }
    }

    private fun showCredentialMenu(credential: CredentialsProfile?, x: Int, y: Int) {
        if (credential == null) return
        JPopupMenu().apply {
            add(JMenuItem(MyMessageBundle.message("credentials.edit")).apply {
                addActionListener { editCredential(credential) }
            })
            add(JMenuItem(MyMessageBundle.message("credentials.remove")).apply {
                addActionListener { removeCredential(credential) }
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
        if (SettingsAccess.folders(project).contains(name)) {
            UploaderNotifications.info(project, MyMessageBundle.message("folder.exists", name))
            return
        }
        SettingsAccess.renameFolder(project, folder, name)
        refresh()
    }

    fun moveProfileToFolder(profile: ServerProfile, folder: String) {
        if (profile.type == ServerType.LOCAL && folder.isNotEmpty()) {
            UploaderNotifications.error(
                project,
                MyMessageBundle.message("server.local.move.unsupported", profile.name),
            )
            return
        }
        profile.folder = folder
        SettingsAccess.save(project, profile)
        refresh()
    }

    private fun deleteFolder(folder: String) {
        val prefix = "$folder/"
        val removedProfiles = SettingsAccess.allServers(project)
            .filter { it.type != ServerType.LOCAL && (it.folder == folder || it.folder.startsWith(prefix)) }
        SettingsAccess.deleteFolder(project, folder)
        removedProfiles.forEach { profile ->
            AppExecutorUtil.getAppExecutorService().execute {
                // A profile owns only its legacy entry; a credentials entity and its password stay in the
                // credentials list, where the user can delete them from.
                PasswordStore.removeOwn(profile.id)
            }
        }
        refresh()
    }

    private fun executeAction(profile: ServerProfile, action: ServerAction) {
        val command = when {
            actionTemplateParams(action.command).isEmpty() -> action.command
            else -> {
                val dialog = ActionInputDialog(project, action.name, actionTemplateParams(action.command))
                if (!dialog.showAndGet()) return
                renderActionCommand(action.command, dialog.values())
            }
        }
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
                    val password = PasswordStore.passwordBytes(profile)
                    if (password == null) {
                        UploaderNotifications.error(
                            project,
                            MyMessageBundle.message("error.password.missing", profile.name),
                        )
                        return
                    }
                    updateProgress()
                    val result = ApplicationManager.getApplication().getService(SftpUploadService::class.java)
                        .executeCommand(profile, PasswordAuthentication(password), command) {
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
        if (profile.type == ServerType.LOCAL) {
            UploaderNotifications.error(
                project,
                MyMessageBundle.message("server.local.delete.unsupported", profile.name),
            )
            return
        }
        SettingsAccess.remove(project, profile.id)
        refresh()
        AppExecutorUtil.getAppExecutorService().execute {
            PasswordStore.removeOwn(profile.id)
        }
    }

    private fun editProfile(existing: ServerProfile?) {
        val dialog = ServerProfileDialog(project, existing)
        if (!dialog.showAndGet()) return
        val selection = dialog.credentials()
        val credential = SettingsAccess.resolveCredential(project, selection)
        val profile = dialog.profile(credential)
        SettingsAccess.remove(project, profile.id)
        SettingsAccess.save(project, profile)
        dialog.rememberChoices(credential)
        refresh()
        val password = selection?.password
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                if (password != null) {
                    PasswordStore.setPassword(profile, profile.username, password)
                }
                // Nothing typed: a server that used to keep its password to itself moves it onto the entry.
                PasswordStore.promoteOwnSecret(profile)
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

    /**
     * Adds an entry or edits the selected one in the credentials folder. The password is typed here, once
     * for every server that picks the entry; blank keeps the stored one.
     */
    private fun editCredential(existing: CredentialsProfile?) {
        val dialog = CredentialsEditDialog(project, existing)
        if (!dialog.showAndGet()) return
        val entry = SettingsAccess.saveCredential(
            project,
            CredentialsProfile(
                id = existing?.id.orEmpty(),
                name = dialog.credentialName(),
                username = dialog.credentialUsername(),
            )
        )
        refresh()
        selectNode(credentialKey(entry.id))
        val password = dialog.password()
        if (password == null) return
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                PasswordStore.setCredential(entry.id, entry.username, password)
            } catch (error: Exception) {
                UploaderNotifications.error(
                    project,
                    MyMessageBundle.message(
                        "password.save.failed",
                        entry.name,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    /**
     * Removes an entry together with its password. When servers still pick it, they are asked about first:
     * the servers are kept and only their credentials are dropped, so each one waits in the server dialog
     * for new ones instead of pointing at an entry that is gone.
     */
    private fun removeCredential(credential: CredentialsProfile) {
        val users = SettingsAccess.profilesUsingCredentials(project, credential.id)
        if (users.isNotEmpty() && !CredentialDeleteDialog(project, credential, users).showAndGet()) return
        SettingsAccess.forgetCredential(project, credential.id)
        refresh()
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                PasswordStore.removeCredential(credential.id)
            } catch (error: Exception) {
                UploaderNotifications.error(
                    project,
                    MyMessageBundle.message(
                        "password.forget.failed",
                        credential.name,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    private fun copyCredentials() {
        val entries = SettingsAccess.credentials(project)
        copyToClipboard(ProfileTransfer.encodeCredentials(entries))
        UploaderNotifications.info(project, MyMessageBundle.message("credentials.copied", entries.size))
    }

    /** Adds the pasted entries; a name already used is sequenced, so nothing saved is overwritten. */
    private fun importCredentials() {
        val dialog = ImportLinesDialog(
            project,
            MyMessageBundle.message("credentials.import"),
            MyMessageBundle.message("import.credentials.hint")
        )
        if (!dialog.showAndGet()) return
        val imported = ProfileTransfer.decodeCredentials(dialog.text())
        if (imported.isEmpty()) return
        imported.forEach { SettingsAccess.saveCredential(project, it) }
        refresh()
        UploaderNotifications.info(project, MyMessageBundle.message("credentials.imported", imported.size))
    }

    private fun testConnection(profile: ServerProfile) {
        object : Task.Backgroundable(project, MyMessageBundle.message("connection.testing", profile.name), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val password = PasswordStore.passwordBytes(profile)
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

    override fun canStartDragging(component: JComponent, pressedOnScreenPoint: Point): Boolean =
        dragged != null

    override fun processMousePressed(event: java.awt.event.MouseEvent) {
        val profile = nodeAt(event.point)?.profile
        dragged = if (event.button == 1 && profile?.type != ServerType.LOCAL) profile else null
    }

    override fun canFinishDragging(event: java.awt.event.MouseEvent): Boolean {
        if (dragged == null) return false
        return nodeAt(event.point)?.isMoveTarget() == true
    }

    override fun processDragFinish(event: java.awt.event.MouseEvent, pressedOnScreen: Boolean) {
        val profile = dragged
        dragged = null
        if (profile != null) {
            nodeAt(event.point)?.let { target ->
                if (target.isMoveTarget()) {
                    panel.moveProfileToFolder(profile, target.path)
                }
            }
        }
    }

    override fun processDrag(
        event: java.awt.event.MouseEvent,
        pressedOnScreenPoint: Point,
        currentOnScreenPoint: Point
    ) {
    }

    private fun nodeAt(point: Point): ServerNode? =
        tree.getPathForLocation(point.x, point.y)?.lastPathComponent as? ServerNode
}

private class ServerNode(
    val name: String,
    val path: String = name,
    val profile: ServerProfile? = null,
    val credential: CredentialsProfile? = null,
    val kind: NodeKind = NodeKind.FOLDER,
) : DefaultMutableTreeNode() {
    val folderChildren: MutableList<ServerNode> = mutableListOf()
    val serverChildren: MutableList<ServerNode> = mutableListOf()
    private val childOrder: List<ServerNode>
        get() = folderChildren + serverChildren

    override fun getUserObject(): Any = this
    override fun isLeaf(): Boolean = childOrder.isEmpty()
    override fun getChildCount(): Int = childOrder.size
    override fun getChildAt(index: Int): TreeNode = childOrder[index]

    /** Servers can be dragged into the **Servers** folder and into any folder of their own. */
    fun isMoveTarget(): Boolean = kind == NodeKind.SERVERS || kind == NodeKind.FOLDER

    val displayName: String
        get() = when (kind) {
            NodeKind.SERVER -> when {
                profile?.type == ServerType.LOCAL -> "\uD83C\uDDE5 " + profile!!.name
                else -> "${profile?.name.orEmpty()} — ${profile?.username.orEmpty()}@${profile?.host.orEmpty()}:${profile?.port}"
            }

            NodeKind.CREDENTIAL -> "${credential?.name.orEmpty()} — ${credential?.username.orEmpty()}"
            else -> name
        }
}

private class FolderDeleteDialog(private val project: Project, private val folder: String) :
    DialogWrapper(project, true) {
    init {
        title = MyMessageBundle.message("folder.remove")
        init()
    }

    override fun createCenterPanel(): JComponent =
        JPanel(BorderLayout()).apply {
            add(JLabel(MyMessageBundle.message("folder.delete.confirm.message", folder)), BorderLayout.CENTER)
        }
}

/** Asks before removing a credentials entry that servers still pick, naming the servers that lose it. */
private class CredentialDeleteDialog(
    project: Project,
    private val credential: CredentialsProfile,
    private val users: List<ServerProfile>,
) : DialogWrapper(project, true) {
    init {
        title = MyMessageBundle.message("credentials.remove")
        init()
    }

    override fun createCenterPanel(): JComponent =
        JPanel(BorderLayout()).apply {
            add(
                JLabel(
                    MyMessageBundle.message(
                        "credentials.delete.confirm",
                        credential.name,
                        users.map { it.name }.joinToString(", "),
                    )
                ),
                BorderLayout.CENTER,
            )
        }
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
        backgroundNonSelectionColor = tree?.background ?: serverPanelBackground
        val component =
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus) as JLabel
        val node = value as? ServerNode
        component.text = node?.displayName.orEmpty()
        component.icon = when (node?.kind) {
            NodeKind.SERVER -> if (node.profile?.type == ServerType.LOCAL) null else this.leafIcon
            NodeKind.CREDENTIAL -> this.leafIcon
            else -> this.openIcon
        }
        return component
    }
}

private class ServerProfileDialog(private val project: Project, private val existing: ServerProfile?) :
    DialogWrapper(project, true) {
    private val isNewProfile = existing == null
    private val isLocal = existing?.type == ServerType.LOCAL
    private val profileId = existing?.id ?: UUID.randomUUID().toString()
    private val newCredentialsItem = MyMessageBundle.message("server.credentials.new")
    private val keepsOwnCredentials = existing != null && existing.credentialsId.isEmpty()
    private val preselectedCredential: CredentialsProfile? = when {
        existing != null -> SftpSettings.getInstance().credential(existing.credentialsId)
        else -> SftpSettings.getInstance().credential(SftpSettings.getInstance().lastCredentialsId)
    }
    private val credentialOptions: List<String> =
        listOf(newCredentialsItem) + SftpSettings.getInstance().credentials().map { it.name }
    private val nameField = JBTextField(existing?.name.orEmpty())
    private val folderField = JBTextField(existing?.folder.orEmpty())
    private val basePathField = JBTextField(existing?.basePath.orEmpty())
    private val hostField = JBTextField(existing?.host.orEmpty())
    private val portField = JSpinner(SpinnerNumberModel(existing?.port ?: 22, 1, 65535, 1))
    private val credentialsCombo = JComboBox<String>(credentialOptions.toTypedArray())
    private val usernameField = JBTextField(existing?.username.orEmpty())
    private val passwordField = JBPasswordField()
    private val passwordPlaceholder = "*".repeat(PASSWORD_PLACEHOLDER_LENGTH)
    private val storeCredentialsCheckbox = JCheckBox(MyMessageBundle.message("server.storeCredentials")).apply {
        // A server that keeps its credentials to itself starts with the box off, so opening it and
        // confirming changes nothing.
        isSelected = !keepsOwnCredentials && SftpSettings.getInstance().newCredentialsStored
        toolTipText = MyMessageBundle.message("server.storeCredentials.tooltip")
    }
    private val credentialsRow = JPanel(BorderLayout(6, 0)).apply {
        isOpaque = false
        add(credentialsCombo, BorderLayout.CENTER)
        add(storeCredentialsCheckbox, BorderLayout.EAST)
    }
    private val useRsyncCheckbox = JCheckBox(MyMessageBundle.message("server.useRsync")).apply {
        isSelected = existing?.useRsync ?: SftpSettings.getInstance().newServerUseRsync
    }
    private val mappingsModel = object : DefaultTableModel(
        arrayOf(
            MyMessageBundle.message("mapping.local"),
            MyMessageBundle.message("mapping.remote"),
        ),
        0,
    ) {
        override fun isCellEditable(row: Int, column: Int) = true
    }
    private val mappingsTable = JBTable(mappingsModel).apply {
        toolTipText = MyMessageBundle.message("server.mappings.tooltip")
        installTableShortcuts(this, mappingsModel) { rows ->
            rows.map { row -> mappingCells(mappingsModel, row) }
        }
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
        installTableShortcuts(this, actionsModel) { rows ->
            val takenNames = actions().map { it.name }.toMutableSet()
            rows.map { row -> actionCells(cloneAction(actionAt(row), takenNames)) }
        }
    }

    init {
        title =
            if (existing == null) MyMessageBundle.message("server.dialog.add") else MyMessageBundle.message("server.dialog.edit")
        credentialsCombo.selectedIndex =
            credentialOptions.indexOf(preselectedCredential?.name ?: newCredentialsItem)
        credentialsCombo.toolTipText = MyMessageBundle.message("server.credentials.tooltip")
        usernameField.toolTipText = MyMessageBundle.message("server.username.tooltip")
        passwordField.toolTipText = MyMessageBundle.message("server.password.tooltip")
        credentialsCombo.addActionListener { showCredentialsOfSelection() }
        showCredentialsOfSelection()
        if (existing == null) {
            mappingsModel.addRow(arrayOf("", ""))
        } else {
            existing.mappings.forEach { mappingsModel.addRow(arrayOf(it.localPath, it.remotePath)) }
            existing.actions.forEach { actionsModel.addRow(arrayOf(it.name, it.command, it.id)) }
        }
        init()
    }

    /**
     * A saved entry owns its username and password, so both fields turn into a read-only view of it: the
     * username as the entry has it, the password as asterisks - the stored secret is never read into this
     * dialog. Both open up only for the new-credentials choice.
     */
    private fun showCredentialsOfSelection() {
        val creating = creatingNewCredentials()
        usernameField.isEnabled = creating
        passwordField.isEnabled = creating
        storeCredentialsCheckbox.isVisible = creating
        if (creating) {
            usernameField.text = existing?.username.orEmpty()
            passwordField.text = ""
        } else {
            usernameField.text = selectedCredential()?.username.orEmpty()
            passwordField.text = passwordPlaceholder
        }
        // The box sits to the right of the combo, so showing or hiding it only takes effect once the
        // row lays itself out again and the combo stretches or shrinks.
        storeCredentialsCheckbox.parent?.let {
            it.revalidate()
            it.repaint()
        }
    }

    /** Keeps the two choices the next server dialog should start from. */
    fun rememberChoices(credential: CredentialsProfile?) {
        if (isLocal) return
        val settings = SftpSettings.getInstance()
        if (creatingNewCredentials()) settings.newCredentialsStored = storeCredentialsCheckbox.isSelected
        settings.lastCredentialsId = credential?.id.orEmpty()
    }

    override fun createCenterPanel(): JComponent {
        val mappingPanel = JPanel(BorderLayout()).apply {
            add(JLabel(MyMessageBundle.message("server.mappings.hint")), BorderLayout.NORTH)
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
        mappingPanel.preferredSize = Dimension(680, 300)
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
                        UploaderNotifications.info(
                            project,
                            MyMessageBundle.message("server.action.copied", actions.size)
                        )
                    }
                })
                add(JButton(MyMessageBundle.message("server.action.import")).apply {
                    addActionListener { importActions() }
                })
            }, BorderLayout.SOUTH)
        }
        actionsPanel.preferredSize = Dimension(680, 160)

        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent(MyMessageBundle.message("server.name"), nameField)
        if (!isLocal) {
            builder.addLabeledComponent(MyMessageBundle.message("server.folder"), folderField)
        }
        if (isLocal) {
            builder.addLabeledComponent(MyMessageBundle.message("server.local.basePath"), basePathField)
        }
        if (!isLocal) {
            builder
                .addLabeledComponent(MyMessageBundle.message("server.host"), hostField)
                .addLabeledComponent(MyMessageBundle.message("server.port"), portField)
                .addLabeledComponent(
                    MyMessageBundle.message("server.credentials", newCredentialsItem),
                    credentialsRow,
                )
                .addLabeledComponent(MyMessageBundle.message("server.username"), usernameField)
                .addLabeledComponent(MyMessageBundle.message("server.password"), passwordField)
                .addComponent(useRsyncCheckbox)
                .addSeparator()
        }
        builder
            .addLabeledComponentFillVertically(MyMessageBundle.message("server.mappings"), mappingPanel)
        if (!isLocal) {
            builder.addLabeledComponentFillVertically(MyMessageBundle.message("server.actions"), actionsPanel)
        }
        return builder.panel
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

        val localPaths = mutableSetOf<String>()
        mappings().forEach { mapping ->
            val local = PathMappingResolver.normalizeLocal(mapping.localPath)
            if (local.split('/').any { it == ".." }) {
                return ValidationInfo(MyMessageBundle.message("validation.local.path"), mappingsTable)
            }
            if (!localPaths.add(local)) {
                return ValidationInfo(MyMessageBundle.message("validation.mapping.duplicate", local), mappingsTable)
            }
            if (!isLocal) {
                try {
                    if (!PathMappingResolver.normalizeRemote(mapping.remotePath).startsWith("/")) {
                        return ValidationInfo(MyMessageBundle.message("validation.remote.absolute"), mappingsTable)
                    }
                } catch (_: IllegalArgumentException) {
                    return ValidationInfo(MyMessageBundle.message("validation.remote.path"), mappingsTable)
                }
            }
        }

        if (isLocal) {
            if (basePathField.text.trim().split('/').any { it == ".." }) {
                return ValidationInfo(MyMessageBundle.message("validation.local.path"), basePathField)
            }
            return null
        }

        if (hostField.text.isBlank()) return ValidationInfo(MyMessageBundle.message("validation.required"), hostField)

        val chosen = selectedCredential()
        if (creatingNewCredentials()) {
            if (usernameField.text.isBlank()) return ValidationInfo(
                MyMessageBundle.message("validation.required"),
                usernameField
            )
            // Credentials typed here need a secret, unless this server already stores its own.
            if (passwordField.password.isEmpty() && !keepsItsOwnPassword())
                return ValidationInfo(MyMessageBundle.message("validation.required"), passwordField)
        } else if (chosen == null) {
            return ValidationInfo(
                MyMessageBundle.message("validation.credentials.missing"),
                credentialsCombo
            )
        } else if (isNewProfile && !SftpSettings.getInstance().hasPassword(chosen.id)) {
            // The password row is read-only for a saved entry, so a new profile cannot fill the gap here.
            return ValidationInfo(
                MyMessageBundle.message("validation.credentials.no.password", chosen.name),
                credentialsCombo
            )
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

    fun profile(credential: CredentialsProfile?): ServerProfile {
        stopEditing()
        return ServerProfile(
            id = profileId,
            name = nameField.text.trim(),
            folder = if (isLocal) "" else folderField.text.trim(),
            host = if (isLocal) "" else hostField.text.trim(),
            port = portField.value as Int,
            username = if (isLocal) "" else credential?.username ?: usernameField.text.trim(),
            credentialsId = credential?.id.orEmpty(),
            useRsync = if (isLocal) false else useRsyncCheckbox.isSelected,
            type = if (isLocal) ServerType.LOCAL else ServerType.SFTP,
            basePath = if (isLocal) basePathField.text.trim() else "",
            mappings = mappings().toMutableList(),
            actions = if (isLocal) mutableListOf() else actions().toMutableList(),
        )
    }

    /**
     * The dialog's credentials choice: the entry picked from the list, or a new one to create with this
     * profile. Null for a local profile, which authenticates with nothing.
     */
    fun credentials(): CredentialSelection? {
        if (isLocal) return null
        if (creatingNewCredentials()) {
            return CredentialSelection(
                existingId = null,
                name = "",
                username = usernameField.text.trim(),
                password = password(),
                storeAsEntity = storeCredentialsCheckbox.isSelected,
            )
        }
        val chosen = selectedCredential()
        return CredentialSelection(
            existingId = chosen?.id,
            name = selectedCredentialName(),
            username = chosen?.username.orEmpty(),
            password = password(),
        )
    }

    /** A server that stores its own password does not have to type it again when it is edited. */
    private fun keepsItsOwnPassword(): Boolean =
        keepsOwnCredentials && existing?.let { PasswordStore.getOwn(it.id) != null } == true

    /** Null while the placeholder stands in for a saved entry's secret, which this dialog never reads. */
    fun password(): String? =
        if (passwordField.isEnabled) passwordField.password.concatToString().takeIf { it.isNotEmpty() }
        else null

    private fun creatingNewCredentials(): Boolean =
        credentialsCombo.selectedItem?.toString() == newCredentialsItem

    private fun selectedCredentialName(): String =
        credentialsCombo.selectedItem?.toString().orEmpty()

    private fun selectedCredential(): CredentialsProfile? =
        if (creatingNewCredentials()) null
        else SftpSettings.getInstance().credentialByName(selectedCredentialName())

    private fun importMappings() {
        val dialog = ImportLinesDialog(
            project,
            MyMessageBundle.message("mapping.import"),
            MyMessageBundle.message("import.mappings.hint")
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
            MyMessageBundle.message("import.actions.hint")
        )
        if (!dialog.showAndGet()) return
        val actions = ProfileTransfer.decodeActions(dialog.text())
        actions.forEach { actionsModel.addRow(arrayOf(it.name, it.command, it.id)) }
        UploaderNotifications.info(project, MyMessageBundle.message("server.action.imported", actions.size))
    }

    private fun mappings(): List<PathMapping> =
        (0 until mappingsModel.rowCount).map { row -> pathMapping(mappingsModel, row) }
            .filterNot { it.localPath.isBlank() && it.remotePath.isBlank() }

    private fun pathMapping(model: DefaultTableModel, row: Int): PathMapping =
        PathMapping(
            localPath = PathMappingResolver.normalizeLocal(model.getValueAt(row, 0)?.toString().orEmpty()),
            remotePath = model.getValueAt(row, 1)?.toString().orEmpty().trim(),
        )

    private fun mappingCells(model: DefaultTableModel, row: Int): Array<String> =
        arrayOf(
            model.getValueAt(row, 0)?.toString().orEmpty(),
            model.getValueAt(row, 1)?.toString().orEmpty(),
        )

    private fun actions(): List<ServerAction> =
        (0 until actionsModel.rowCount).map { row -> actionAt(row) }

    private fun actionAt(row: Int): ServerAction =
        ServerAction(
            id = actionsModel.getValueAt(row, 2)?.toString().orEmpty(),
            name = actionsModel.getValueAt(row, 0)?.toString().orEmpty().trim(),
            command = actionsModel.getValueAt(row, 1)?.toString().orEmpty().trim(),
        )

    private fun actionCells(action: ServerAction): Array<String> =
        arrayOf(action.name, action.command, action.id)

    private fun stopEditing() {
        if (mappingsTable.isEditing) mappingsTable.cellEditor.stopCellEditing()
        if (actionsTable.isEditing) actionsTable.cellEditor.stopCellEditing()
    }
}

/** Adds a credentials entry or edits one from the **Credentials** folder. */
private class CredentialsEditDialog(private val project: Project, private val existing: CredentialsProfile?) :
    DialogWrapper(project, true) {
    private val nameField = JBTextField(existing?.name.orEmpty())
    private val usernameField = JBTextField(existing?.username.orEmpty())
    private val passwordField = JBPasswordField()

    init {
        title = MyMessageBundle.message("credentials.dialog")
        init()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(MyMessageBundle.message("credentials.name"), nameField)
            .addLabeledComponent(MyMessageBundle.message("credentials.username"), usernameField)
            .addLabeledComponent(
                MyMessageBundle.message(
                    if (existing == null) "credentials.password" else "credentials.password.unchanged"
                ),
                passwordField,
            )
            .addComponent(
                JLabel(
                    MyMessageBundle.message(
                        if (existing != null && SftpSettings.getInstance().hasPassword(existing.id))
                            "credentials.password.stored"
                        else
                            "credentials.password.unstored"
                    )
                )
            )
            .addComponentFillVertically(JLabel(MyMessageBundle.message("credentials.hint")), 0)
            .panel

    fun credentialName(): String = nameField.text.trim()

    fun credentialUsername(): String = usernameField.text.trim()

    fun password(): String? = passwordField.password.concatToString().takeIf { it.isNotEmpty() }

    override fun doOKAction() {
        val validation = validateEntry()
        if (validation != null) {
            setErrorText(validation.message)
            validation.component?.requestFocusInWindow()
            return
        }
        setErrorText(null)
        super.doOKAction()
    }

    override fun doValidate(): ValidationInfo? = null

    private fun validateEntry(): ValidationInfo? {
        if (credentialName().isBlank()) return ValidationInfo(MyMessageBundle.message("validation.required"), nameField)
        if (credentialUsername().isBlank())
            return ValidationInfo(MyMessageBundle.message("validation.required"), usernameField)
        val taken = SftpSettings.getInstance().credentialByName(credentialName())
        if (taken != null && taken.id != existing?.id)
            return ValidationInfo(MyMessageBundle.message("validation.credentials.name.duplicate"), nameField)
        // An entry without a password could never authenticate, so the first one has to be typed here.
        if (existing == null && password() == null)
            return ValidationInfo(MyMessageBundle.message("validation.required"), passwordField)
        return null
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

private class ActionInputDialog(
    project: Project,
    actionName: String,
    private val params: List<String>,
) : DialogWrapper(project, true) {
    private val fields = params.map { param -> JBTextField() }

    init {
        title = MyMessageBundle.message("action.template.dialog.title", actionName)
        init()
    }

    fun values(): Map<String, String> =
        params.mapIndexed { index, param -> param to fields[index].text }.toMap()

    override fun createCenterPanel(): JComponent {
        val builder = FormBuilder.createFormBuilder()
        params.forEachIndexed { index, param ->
            builder.addLabeledComponent("$param:", fields[index])
        }
        return builder.panel
    }
}
