package org.kavo.uploader.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import org.kavo.uploader.settings.ServerProfile
import org.kavo.uploader.settings.SftpSettings
import org.kavo.uploader.settings.pathSegments
import org.kavo.uploader.settings.ServerType

fun serverMenuChildren(
    factory: (ServerProfile) -> AnAction,
): Array<AnAction> = serverMenuChildren(SftpSettings.getInstance().remoteServers(), factory)

fun localMenuChildren(
    factory: (ServerProfile) -> AnAction,
): Array<AnAction> = serverMenuChildren(SftpSettings.getInstance().localServers(), factory)

fun serverMenuChildren(
    servers: List<ServerProfile>,
    factory: (ServerProfile) -> AnAction,
): Array<AnAction> = buildGroup(servers, 0, factory).getChildActionsOrStubs()

private fun buildGroup(
    servers: List<ServerProfile>,
    segmentIndex: Int,
    factory: (ServerProfile) -> AnAction,
): DefaultActionGroup {
    val group = DefaultActionGroup()
    val grouped =
        servers.filter { pathSegments(it.folder).size > segmentIndex }.groupBy {
            pathSegments(it.folder)[segmentIndex]
        }
    grouped.keys.sorted().forEach { segment ->
        val members = grouped.getValue(segment)
        val submenu = DefaultActionGroup(segment, true)
        members
            .filter { pathSegments(it.folder).size == segmentIndex + 1 }
            .forEach { submenu.add(factory(it)) }
        buildGroup(members, segmentIndex + 1, factory).getChildActionsOrStubs()
            .forEach { submenu.add(it) }
        group.add(submenu)
    }

    if (segmentIndex == 0) {
        servers
            .filter { pathSegments(it.folder).isEmpty() }
            .forEach { group.add(factory(it)) }
    }
    return group
}
