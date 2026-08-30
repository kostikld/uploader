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
    private val checkbox = JCheckBox(MyMessageBundle.message("server.withInnerClasses")).apply {
        isSelected = settings.withInnerClasses
     }
    private val panel = JPanel(BorderLayout()).apply {
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(checkbox) }, BorderLayout.NORTH)
      }

    override fun getDisplayName() = MyMessageBundle.message("settings.title")

    override fun createComponent(): JComponent = panel

    override fun isModified() = settings.withInnerClasses != checkbox.isSelected

    override fun apply() {
        settings.withInnerClasses = checkbox.isSelected
       }

    override fun reset() {
        checkbox.isSelected = settings.withInnerClasses
       }

    override fun getPreferredFocusedComponent() = checkbox
}
