package io.xymul.xmpp.xmake2cmake.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import io.xymul.xmpp.xmake2cmake.generation.AutoGenerationCoordinator
import io.xymul.xmpp.xmake2cmake.settings.GenerationPolicy
import io.xymul.xmpp.xmake2cmake.settings.XMake2CMakeSettings
import io.xymul.xmpp.xmake2cmake.xmake.XMakeLuaFiles

/**
 * "Force Update CMakeLists.txt": regenerate `CMakeLists.txt` from `xmake.lua` on explicit request.
 *
 * Behaviour:
 *  - if the `xmake.lua` document has unsaved changes, the changes are saved like an ordinary save
 *    gesture, and the regular pipeline regenerates `CMakeLists.txt` (subject to the configured policy);
 *    when automatic generation is switched off, the generation is forced instead, because the user
 *    asked for it explicitly;
 *  - if nothing changed, a dialog asks whether the update should be forced.
 *
 * The action is only enabled for `xmake.lua` files of the current project, which also means that for
 * every other file the Ctrl+S default shortcut falls through to the platform's "Save All".
 */
class ForceUpdateCMakeListsAction : AnAction() {

    // The update runs on the EDT: the data context (selected file) is only reliably available there.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = targetFile(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = targetFile(e)
        if (file == null) {
            // Safety net: this action is disabled for everything but xmake.lua, but should the platform
            // dispatch the shortcut to it anyway, keep the ordinary save semantics of Ctrl+S.
            LOG.info("Automatic XMake2CMake: force update action invoked without an xmake.lua, saving documents")
            FileDocumentManager.getInstance().saveAllDocuments()
            return
        }
        LOG.info("Automatic XMake2CMake: force update action performed for ${file.path}")

        val documentManager = FileDocumentManager.getInstance()
        val document = documentManager.getCachedDocument(file)
        if (document != null && documentManager.isDocumentUnsaved(document)) {
            // There are real changes: save them like the platform "Save All" does (for every document,
            // not only for xmake.lua).
            documentManager.saveAllDocuments()
            if (XMake2CMakeSettings.getInstance().normalized().generationPolicy != GenerationPolicy.DISABLED) {
                // Saving already started the regular pipeline, nothing else to do here.
                return
            }
            // Automatic generation is switched off, but the user asked for an update explicitly,
            // and the description on disk has just changed, so no extra question is needed.
            AutoGenerationCoordinator.generateNow(project, file)
            return
        }
        AutoGenerationCoordinator.forceUpdate(project, file)
    }

    /** The `xmake.lua` the action was invoked on, or `null` when it is not applicable. */
    private fun targetFile(e: AnActionEvent): VirtualFile? {
        val project = e.project ?: return null
        if (project.isDisposed) return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        if (!file.isValid || !XMakeLuaFiles.isXMakeLuaFileName(file.name)) return null
        val basePath = project.basePath ?: return null
        return if (FileUtil.isAncestor(basePath, file.path, false)) file else null
    }

    companion object {
        /** Must match the `id` of the `<action>` extension in `plugin.xml`. */
        const val ACTION_ID: String = "Automatic-XMake2CMake.ForceUpdateCMakeLists"

        private val LOG: Logger = Logger.getInstance(ForceUpdateCMakeListsAction::class.java)
    }
}
