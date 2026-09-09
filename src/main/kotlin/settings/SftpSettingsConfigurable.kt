package org.kavo.uploader.settings

import com.intellij.openapi.options.Configurable
import org.kavo.uploader.MyMessageBundle
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

class SftpSettingsConfigurable : Configurable {
    private val settings = SftpSettings.getInstance()
    private val innerClassesCheckbox = JCheckBox(MyMessageBundle.message("server.withInnerClasses")).apply {
        isSelected = settings.withInnerClasses
        }
    private val javaClassFilesCheckbox = JCheckBox(MyMessageBundle.message("server.uploadJavaClassFiles")).apply {
        isSelected = settings.uploadJavaClassFiles
        }
    private val panel = JPanel(BorderLayout()).apply {
        add(
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(innerClassesCheckbox)
                add(javaClassFilesCheckbox)
               },
            BorderLayout.NORTH,
          )
        }

    override fun getDisplayName() = MyMessageBundle.message("settings.title")

    override fun createComponent(): JComponent = panel

    override fun isModified() =
        settings.withInnerClasses != innerClassesCheckbox.isSelected ||
            settings.uploadJavaClassFiles != javaClassFilesCheckbox.isSelected

    override fun apply() {
        settings.withInnerClasses = innerClassesCheckbox.isSelected
        settings.uploadJavaClassFiles = javaClassFilesCheckbox.isSelected
        }

    override fun reset() {
        innerClassesCheckbox.isSelected = settings.withInnerClasses
        javaClassFilesCheckbox.isSelected = settings.uploadJavaClassFiles
        }

    override fun getPreferredFocusedComponent() = innerClassesCheckbox
}
