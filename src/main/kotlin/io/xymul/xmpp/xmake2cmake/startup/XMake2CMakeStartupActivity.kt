package io.xymul.xmpp.xmake2cmake.startup

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.xymul.xmpp.xmake2cmake.listener.XMake2CMakeProjectService

/**
 * Instantiates [XMake2CMakeProjectService] on project open.
 *
 * Registered through the `postStartupActivity` extension point, which is bound to
 * `com.intellij.openapi.startup.ProjectActivity` since 2026.2.
 */
class XMake2CMakeStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Requesting the service installs the xmake.lua listeners.
        project.service<XMake2CMakeProjectService>()
    }
}
