package io.xymul.xmpp.xmake2cmake.xmake

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.Method

/**
 * Best effort bridge to the classes of the [XMake IntelliJ plugin](https://plugins.jetbrains.com/plugin/17406-xmake).
 *
 * The XMake plugin (id `io.xmake`) owns the toolkit configuration, i.e. the path of the `xmake`
 * executable the user selected for a project. This class reads that path so that
 * Automatic XMake2CMake uses exactly the same xmake installation.
 *
 * The bridge is implemented with reflection on purpose:
 *  - the plugin is declared as an *optional* dependency, so this class has to stay loadable when the
 *    XMake plugin is missing, and
 *  - the toolkit API of `io.xmake` changed between releases (e.g. `activatedToolkit` was replaced by
 *    the `ToolkitManager`/`XMakeBuildProfile` split), so nothing may be linked at compile time.
 *
 * Every lookup is guarded: when anything goes wrong the method returns `null` and the caller falls
 * back to the `PATH` (or the path configured in the settings page).
 */
object XMakeToolkitBridge {

    private val LOG: Logger = Logger.getInstance(XMakeToolkitBridge::class.java)

    private const val TOOLKIT_MANAGER = "io.xmake.project.toolkit.ToolkitManager"
    private const val BUILD_PROFILE_MANAGER_KT = "io.xmake.project.profile.XMakeBuildProfileManagerKt"

    private val TOOLKIT_COLLECTIONS = listOf("getRegisteredToolkits", "getVisibleToolkits", "getToolkits")

    private val TOOLKIT_PATH_GETTERS = listOf("getPath")

    /** Returns the xmake executable configured in the XMake plugin, or `null` when it cannot be read. */
    fun findXMakeExecutable(project: Project): String? {
        if (project.isDisposed) return null
        return try {
            fromToolkitManager(project) ?: fromBuildProfiles(project)
        } catch (t: Throwable) {
            // NoClassDefFoundError etc. are expected when the XMake plugin is not installed.
            LOG.debug("Automatic XMake2CMake: XMake plugin toolkit lookup failed", t)
            null
        }
    }

    private fun fromToolkitManager(project: Project): String? {
        val managerClass = loadClass(TOOLKIT_MANAGER) ?: return null
        val manager = invokeGetInstance(managerClass, project) ?: return null
        for (getter in TOOLKIT_COLLECTIONS) {
            val toolkits = invokeNoArg(manager, getter) as? Collection<*> ?: continue
            val candidates = toolkits.mapNotNull { extractPath(it) }
            pickUsable(candidates)?.let { return it }
        }
        return null
    }

    private fun fromBuildProfiles(project: Project): String? {
        val facadeClass = loadClass(BUILD_PROFILE_MANAGER_KT) ?: return null
        val manager = invokeStatic(facadeClass, "getXmakeBuildProfiles", project, Project::class.java) ?: return null
        val profiles = invokeNoArg(manager, "getProfiles") as? Collection<*> ?: return null
        val candidates = mutableListOf<String>()
        for (profile in profiles) {
            if (profile == null) continue
            // A profile either knows its toolkit directly or only its id, which we cannot resolve here.
            val toolkit = invokeNoArg(profile, "resolveToolkit") ?: continue
            extractPath(toolkit)?.let { candidates.add(it) }
        }
        return pickUsable(candidates)
    }

    private fun extractPath(toolkit: Any?): String? {
        if (toolkit == null) return null
        // Only toolkits that are actually usable may be used.
        val available = invokeNoArg(toolkit, "isAvailable")
        if (available is Boolean && !available) return null
        for (getter in TOOLKIT_PATH_GETTERS) {
            val path = invokeNoArg(toolkit, getter) as? String ?: continue
            if (path.isNotBlank() && File(path).isFile && isXMakeExecutableName(path)) {
                return path
            }
        }
        return null
    }

    private fun pickUsable(candidates: List<String>): String? = candidates.firstOrNull()

    private fun isXMakeExecutableName(path: String): Boolean {
        val name = XMakeLuaFiles.fileNameOf(path).lowercase()
        return name == "xmake" || name == "xmake.exe" || name.startsWith("xmake.")
    }

    private fun loadClass(className: String): Class<*>? = try {
        Class.forName(className, false, XMakeToolkitBridge::class.java.classLoader)
    } catch (t: Throwable) {
        null
    }

    /** `ToolkitManager.getInstance(project)` is generated either as a static method or on the Kotlin companion object. */
    private fun invokeGetInstance(clazz: Class<*>, project: Project): Any? {
        invokeStatic(clazz, "getInstance", project, Project::class.java)?.let { return it }
        val companion = try {
            clazz.getField("Companion").get(null)
        } catch (t: Throwable) {
            null
        } ?: return null
        return invokeStatic(companion.javaClass, "getInstance", project, Project::class.java)
    }

    private fun invokeStatic(target: Any, name: String, argument: Any, argumentType: Class<*>): Any? = try {
        val method: Method = target.javaClass.getMethod(name, argumentType)
        method.invoke(if (target is Class<*>) null else target, argument)
    } catch (t: Throwable) {
        null
    }

    private fun invokeNoArg(target: Any, name: String): Any? = try {
        val method = target.javaClass.getMethod(name)
        method.isAccessible = true
        method.invoke(target)
    } catch (t: Throwable) {
        null
    }
}
