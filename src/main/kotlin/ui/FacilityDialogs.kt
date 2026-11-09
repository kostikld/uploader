package org.kavo.uploader.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import org.kavo.uploader.MyMessageBundle
import org.kavo.uploader.settings.Facility
import org.kavo.uploader.settings.ServerActionValidationError
import org.kavo.uploader.settings.ServerProfile
import org.kavo.uploader.settings.SettingsAccess
import org.kavo.uploader.settings.validateServerActions
import org.kavo.uploader.upload.PathMappingResolver
import java.awt.BorderLayout
import java.util.UUID
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** Asks before removing a facility that servers still pick, naming the servers that lose it. */
internal class FacilityDeleteDialog(
    project: Project,
    private val facility: Facility,
    private val users: List<ServerProfile>,
) : DialogWrapper(project, true) {
    init {
        title = MyMessageBundle.message("facility.remove")
        init()
    }

    override fun createCenterPanel(): JComponent =
        JPanel(BorderLayout()).apply {
            add(
                JLabel(
                    MyMessageBundle.message(
                        "facility.delete.confirm",
                        facility.name,
                        users.map { it.name }.joinToString(", "),
                    )
                ),
                BorderLayout.CENTER,
            )
        }
}

/** Adds a facility or edits one: a name, the mappings and the actions its servers inherit. */
internal class FacilityEditDialog(private val project: Project, private val existing: Facility?) :
    DialogWrapper(project, true) {
    private val facilityId = existing?.id ?: UUID.randomUUID().toString()
    private val nameField = JBTextField(existing?.name.orEmpty())
    private val tables = MappingActionTables(project)

    init {
        title = if (existing == null)
            MyMessageBundle.message("facility.dialog.add")
        else
            MyMessageBundle.message("facility.dialog.edit")
        if (existing == null) {
            tables.mappingsModel.addRow(arrayOf("", ""))
        } else {
            existing.mappings.forEach { tables.mappingsModel.addRow(arrayOf(it.localPath, it.remotePath)) }
            existing.actions.forEach { tables.actionsModel.addRow(arrayOf(it.name, it.command, it.id)) }
        }
        init()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(MyMessageBundle.message("facility.name"), nameField)
            .addComponentFillVertically(JLabel(MyMessageBundle.message("facility.hint")), 0)
            .addLabeledComponentFillVertically(MyMessageBundle.message("server.mappings"), tables.mappingPanel())
            .addLabeledComponentFillVertically(MyMessageBundle.message("server.actions"), tables.actionsPanel())
            .panel

    fun facility(): Facility {
        tables.stopEditing()
        return Facility(
            id = facilityId,
            name = nameField.text.trim(),
            mappings = tables.mappings().toMutableList(),
            actions = tables.actions().toMutableList(),
        )
    }

    override fun doOKAction() {
        tables.stopEditing()
        val validation = validateFacility()
        if (validation != null) {
            setErrorText(validation.message)
            validation.component?.requestFocusInWindow()
            return
        }
        setErrorText(null)
        super.doOKAction()
    }

    override fun doValidate(): ValidationInfo? = null

    /** The same rules as a server's own rows: a facility feeds SFTP servers, so remotes stay absolute. */
    private fun validateFacility(): ValidationInfo? {
        if (nameField.text.isBlank())
            return ValidationInfo(MyMessageBundle.message("validation.required"), nameField)
        val taken = SettingsAccess.facilities(project).firstOrNull { it.name == nameField.text.trim() }
        if (taken != null && taken.id != existing?.id)
            return ValidationInfo(MyMessageBundle.message("validation.facility.name.duplicate"), nameField)

        val localPaths = mutableSetOf<String>()
        tables.mappings().forEach { mapping ->
            val local = PathMappingResolver.normalizeLocal(mapping.localPath)
            if (local.split('/').any { it == ".." }) {
                return ValidationInfo(MyMessageBundle.message("validation.local.path"), tables.mappingsTable)
            }
            if (!localPaths.add(local)) {
                return ValidationInfo(
                    MyMessageBundle.message("validation.mapping.duplicate", local),
                    tables.mappingsTable,
                )
            }
            try {
                if (!PathMappingResolver.normalizeRemote(mapping.remotePath).startsWith("/")) {
                    return ValidationInfo(
                        MyMessageBundle.message("validation.remote.absolute"),
                        tables.mappingsTable,
                    )
                }
            } catch (_: IllegalArgumentException) {
                return ValidationInfo(MyMessageBundle.message("validation.remote.path"), tables.mappingsTable)
            }
        }

        when (validateServerActions(tables.actions())) {
            ServerActionValidationError.BLANK_NAME ->
                return ValidationInfo(MyMessageBundle.message("validation.action.name.required"), tables.actionsTable)

            ServerActionValidationError.BLANK_COMMAND ->
                return ValidationInfo(MyMessageBundle.message("validation.action.command.required"), tables.actionsTable)

            ServerActionValidationError.DUPLICATE_NAME ->
                return ValidationInfo(MyMessageBundle.message("validation.action.name.duplicate"), tables.actionsTable)

            null -> Unit
        }
        return null
    }
}
