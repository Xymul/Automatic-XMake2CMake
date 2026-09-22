package io.xymul.xmpp.xmake2cmake.xmake

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.xymul.xmpp.xmake2cmake.settings.XMake2CMakeSettings
import java.io.File

/**
 * Finds the `xmake` executable to use.
 *
 * Resolution order:
 * 1. the path configured in the settings page,
 * 2. the toolkit configured in the XMake plugin (see [XMakeToolkitBridge]),
 * 3. the `PATH` of the IDE process,
 * 4. well known installation directories.
 */
object XMakeExecutableResolver {

    private val LOG: Logger = Logger.getInstance(XMakeExecutableResolver::class.java)

    private val IS_WINDOWS: Boolean = File.separatorChar == '\\'

    data class Executable(val path: String, val source: String)

    fun resolve(project: Project?): Executable? {
        fromSettings()?.let { return it }
        if (project != null) {
            fromXMakePlugin(project)?.let { return it }
        }
        fromPath()?.let { return it }
        fromKnownLocations()?.let { return it }
        LOG.warn("Automatic XMake2CMake: no xmake executable found")
        return null
    }

    private fun fromSettings(): Executable? {
        val configured = XMake2CMakeSettings.getInstance().normalized().xmakeExecutablePath.trim()
        if (configured.isEmpty()) return null
        val file = File(configured)
        return if (file.isFile) Executable(file.absolutePath, "settings") else null
    }

    private fun fromXMakePlugin(project: Project): Executable? =
        XMakeToolkitBridge.findXMakeExecutable(project)?.let { Executable(it, "XMake plugin toolkit") }

    private fun fromPath(): Executable? {
        val path = System.getenv("PATH") ?: return null
        for (directory in path.split(File.pathSeparatorChar)) {
            if (directory.isBlank()) continue
            for (name in executableNames()) {
                val candidate = File(directory.trim(), name)
                if (candidate.isFile) {
                    return Executable(candidate.absolutePath, "PATH")
                }
            }
        }
        return null
    }

    private fun fromKnownLocations(): Executable? {
        val directories = LinkedHashSet<File>()
        System.getenv("XMAKE_ROOT")?.takeIf { it.isNotBlank() }?.let { directories.add(File(it.trim())) }
        System.getenv("XMAKE_GLOBALDIR")?.takeIf { it.isNotBlank() }?.let { globalDir ->
            // Typically <install dir>/xmake_global, so <install dir>/xmake is a good guess.
            File(globalDir.trim()).parentFile?.let { directories.add(File(it, "xmake")) }
        }
        System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { directories.add(File(it, "xmake")) }

        for (directory in directories) {
            for (name in executableNames()) {
                val candidate = File(directory, name)
                if (candidate.isFile) {
                    return Executable(candidate.absolutePath, "known location")
                }
            }
        }
        return null
    }

    private fun executableNames(): List<String> =
        if (IS_WINDOWS) listOf("xmake.exe", "xmake.cmd", "xmake.bat", "xmake") else listOf("xmake")
}
