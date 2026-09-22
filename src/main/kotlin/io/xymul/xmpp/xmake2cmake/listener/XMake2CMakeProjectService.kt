package io.xymul.xmpp.xmake2cmake.listener

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import io.xymul.xmpp.xmake2cmake.generation.AutoGenerationCoordinator
import io.xymul.xmpp.xmake2cmake.settings.GenerationPolicy
import io.xymul.xmpp.xmake2cmake.settings.XMake2CMakeSettings
import io.xymul.xmpp.xmake2cmake.xmake.XMakeLuaFiles
import java.awt.event.InputEvent
import java.awt.event.MouseEvent

/**
 * Project service that installs the two triggers for automatic CMakeLists.txt generation:
 *
 *  - `Ctrl+S`: [FileDocumentManagerListener.beforeDocumentSaving] is called for every document that
 *    actually contains changes and is about to be written to disk.
 *  - closing the editor tab: [FileEditorManagerListener.fileClosed] is called after the last editor
 *    of the file was closed; changes still kept in memory are saved first.
 *
 * Subscriptions are bound to this service, i.e. they are removed when the project is closed.
 */
@Service(Service.Level.PROJECT)
class XMake2CMakeProjectService(private val project: Project) : Disposable {

    private val LOG: Logger = Logger.getInstance(XMake2CMakeProjectService::class.java)

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    val file = FileDocumentManager.getInstance().getFile(document) ?: return
                    if (!belongsToProject(file)) return
                    AutoGenerationCoordinator.onXMakeLuaChanged(project, file)
                }
            })

        project.messageBus.connect(this)
            .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (!belongsToProject(file)) return
                    val documentManager = FileDocumentManager.getInstance()
                    val document = documentManager.getCachedDocument(file)
                    if (document != null && documentManager.isDocumentUnsaved(document)) {
                        // Closing a tab keeps unsaved changes in memory: write them to disk first so that
                        // the xmake command line below sees the new project description.
                        documentManager.saveDocument(document)
                    }
                    AutoGenerationCoordinator.onXMakeLuaChanged(project, file)
                }
            })

        // Ctrl+S is also assigned to the platform action "Save All" and the platform may dispatch the
        // keystroke to that action instead of to the force update one. This listener covers that case:
        // when a save gesture is performed for an unchanged xmake.lua of this project, the force update
        // is requested here as well, so Ctrl+S behaves the same whichever action wins the keystroke.
        // Menu/mouse invocations are ignored, they keep the plain save behaviour.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(AnActionListener.TOPIC, object : AnActionListener {
                override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
                    if (event.project != project) return
                    val actionId = ActionManager.getInstance().getId(action) ?: return
                    if (actionId !in SAVE_ACTION_IDS) return

                    val inputEvent: InputEvent? = event.inputEvent
                    if (inputEvent is MouseEvent) return

                    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
                    if (file == null || !belongsToProject(file)) return

                    val documentManager = FileDocumentManager.getInstance()
                    val document = documentManager.getCachedDocument(file)
                    val hasUnsavedChanges = document != null && documentManager.isDocumentUnsaved(document)
                    LOG.info(
                        "Automatic XMake2CMake: save gesture '$actionId' for ${file.path} " +
                                "(input=${inputEvent?.javaClass?.simpleName}, unsaved=$hasUnsavedChanges)"
                    )
                    if (hasUnsavedChanges) {
                        // Real changes are handled by the save pipeline that runs right after this event.
                        return
                    }
                    if (XMake2CMakeSettings.getInstance().normalized().generationPolicy ==
                        GenerationPolicy.DISABLED
                    ) {
                        LOG.info("Automatic XMake2CMake: automatic generation is disabled, no force update dialog")
                        return
                    }
                    LOG.info("Automatic XMake2CMake: xmake.lua is unchanged, offering a forced update")
                    // The dialog must not be opened inside this callback: the platform forbids AWT events
                    // (which a modal dialog pumps) inside fireBeforeActionPerformed and reports an
                    // internal error otherwise. Deferring it to the next EDT event keeps the action
                    // dispatch intact.
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || !file.isValid) return@invokeLater
                        AutoGenerationCoordinator.forceUpdate(project, file)
                    }
                }
            })
    }

    /** `true` for an `xmake.lua` that belongs to this project. */
    private fun belongsToProject(file: VirtualFile): Boolean {
        if (!file.isValid) return false
        if (!XMakeLuaFiles.isXMakeLuaFileName(file.name)) return false
        val basePath = project.basePath ?: return false
        return FileUtil.isAncestor(basePath, file.path, false)
    }

    override fun dispose() {
        // The message bus connections are disposed together with this service.
    }

    private companion object {
        /** Action ids of the platform save gestures, see the `$default` keymap. */
        private val SAVE_ACTION_IDS = setOf("SaveAll", "\$SaveAll")
    }
}
