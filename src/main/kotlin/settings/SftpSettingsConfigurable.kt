package org.kavo.uploader.settings

import com.intellij.openapi.options.Configurable
import org.kavo.uploader.MyMessageBundle
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class SftpSettingsConfigurable : Configurable {
    private val settings = SftpSettings.getInstance()
    private val innerClassesCheckbox = JCheckBox(MyMessageBundle.message("server.withInnerClasses")).apply {
        isSelected = settings.withInnerClasses
    }
    private val javaClassFilesCheckbox = JCheckBox(MyMessageBundle.message("server.uploadJavaClassFiles")).apply {
        isSelected = settings.uploadJavaClassFiles
    }
    private val newServerUseRsyncCheckbox = JCheckBox(MyMessageBundle.message("server.newServerUseRsync")).apply {
        isSelected = settings.newServerUseRsync
    }
    private val addFolderButtonCheckbox = JCheckBox(MyMessageBundle.message("settings.window.showAddFolderButton")).apply {
        isSelected = settings.showMenuAddFolderButton
    }
    private val wrapButtonsCheckbox = JCheckBox(MyMessageBundle.message("settings.window.wrapButtons")).apply {
        isSelected = settings.wrapMenuButtons
    }
    private val panel = JPanel(BorderLayout()).apply {
        add(
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(innerClassesCheckbox)
                add(javaClassFilesCheckbox)
                add(newServerUseRsyncCheckbox)
            },
            BorderLayout.NORTH,
        )
        add(
            JPanel(BorderLayout()).apply {
                add(
                    JLabel(MyMessageBundle.message("settings.window.title")).apply {
                        font = font.deriveFont(Font.BOLD)
                    },
                    BorderLayout.NORTH,
                )
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                        add(addFolderButtonCheckbox)
                        add(wrapButtonsCheckbox)
                    },
                    BorderLayout.CENTER,
                )
            },
            BorderLayout.CENTER,
        )
    }

    override fun getDisplayName() = MyMessageBundle.message("settings.title")

    override fun createComponent(): JComponent = panel

    override fun isModified() =
        settings.withInnerClasses != innerClassesCheckbox.isSelected ||
            settings.uploadJavaClassFiles != javaClassFilesCheckbox.isSelected ||
            settings.newServerUseRsync != newServerUseRsyncCheckbox.isSelected ||
            settings.showMenuAddFolderButton != addFolderButtonCheckbox.isSelected ||
            settings.wrapMenuButtons != wrapButtonsCheckbox.isSelected

    override fun apply() {
        settings.withInnerClasses = innerClassesCheckbox.isSelected
        settings.uploadJavaClassFiles = javaClassFilesCheckbox.isSelected
        settings.newServerUseRsync = newServerUseRsyncCheckbox.isSelected
        settings.showMenuAddFolderButton = addFolderButtonCheckbox.isSelected
        settings.wrapMenuButtons = wrapButtonsCheckbox.isSelected
    }

    override fun reset() {
        innerClassesCheckbox.isSelected = settings.withInnerClasses
        javaClassFilesCheckbox.isSelected = settings.uploadJavaClassFiles
        newServerUseRsyncCheckbox.isSelected = settings.newServerUseRsync
        addFolderButtonCheckbox.isSelected = settings.showMenuAddFolderButton
        wrapButtonsCheckbox.isSelected = settings.wrapMenuButtons
    }

    override fun getPreferredFocusedComponent() = innerClassesCheckbox
}
