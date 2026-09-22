package io.xymul.xmpp.xmake2cmake.notification

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Thin wrapper around the notification group declared in `plugin.xml`.
 */
object XMake2CMakeNotifier {

    /** Must match the id of the `notificationGroup` extension in `plugin.xml`. */
    private const val GROUP_ID = "Automatic XMake2CMake.Notification"

    private const val TITLE = "Automatic XMake2CMake"

    fun info(project: Project?, content: String) = notify(project, content, NotificationType.INFORMATION)

    fun warning(project: Project?, content: String) = notify(project, content, NotificationType.WARNING)

    fun error(project: Project?, content: String) = notify(project, content, NotificationType.ERROR)

    private fun notify(project: Project?, content: String, type: NotificationType): Notification {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
        val notification = group.createNotification(TITLE, content, type)
        notification.notify(project)
        return notification
    }
}
