package org.kavo.uploader

import org.kavo.uploader.upload.isSuccessfulCommandExit

internal data class ActionExecutionNotification(
    val content: String,
    val isInformational: Boolean,
)

internal fun formatActionExecutionNotification(
    exitStatus: Int,
    status: String,
    standardOutput: String,
    isOutputTruncated: Boolean,
    truncationMarker: String,
    completion: String,
): ActionExecutionNotification {
    val lines = mutableListOf(status)
    if (standardOutput.isNotBlank()) lines += standardOutput.toNotificationHtml()
    if (isOutputTruncated) lines += truncationMarker
    lines += completion
    return ActionExecutionNotification(lines.joinToString("<br>"), isSuccessfulCommandExit(exitStatus))
}

private fun String.toNotificationHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\n", "<br>")