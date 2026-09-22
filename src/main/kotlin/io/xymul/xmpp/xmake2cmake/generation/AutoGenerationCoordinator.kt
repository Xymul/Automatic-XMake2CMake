package io.xymul.xmpp.xmake2cmake.generation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import io.xymul.xmpp.xmake2cmake.notification.XMake2CMakeNotifier
import io.xymul.xmpp.xmake2cmake.settings.GenerationPolicy
import io.xymul.xmpp.xmake2cmake.settings.XMake2CMakeSettings
import io.xymul.xmpp.xmake2cmake.xmake.XMakeLuaFiles
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides what happens after an `xmake.lua` was modified and, if requested, triggers the generation.
 *
 * Stability rules:
 *  - a single save is usually reported twice (the document is saved before a tab is closed), so events
 *    are coalesced with a per file timestamp window;
 *  - the generation is serialised per file: while a run is in progress at most one additional run is
 *    queued and every further request is merged into it, so `xmake` never runs twice for the same
 *    `xmake.lua` at the same time;
 *  - explicit requests (the force update action and its shortcut) are throttled as well and never open a
 *    dialog while a generation is running - pressing Ctrl+S repeatedly cannot pile up dialogs or tasks.
 */
object AutoGenerationCoordinator {

    private val LOG: Logger = Logger.getInstance(AutoGenerationCoordinator::class.java)

    private const val COALESCE_WINDOW_MILLIS = 2_000L

    /** Repeated explicit requests for the same file inside this window do not open a second dialog. */
    private const val EXPLICIT_REQUEST_THROTTLE_MILLIS = 1_500L

    private val lastHandled = ConcurrentHashMap<String, Long>()

    private val lastExplicitRequest = ConcurrentHashMap<String, Long>()

    /** Per file generation state, see [RunState]. */
    private val runStates = ConcurrentHashMap<String, RunState>()

    /**
     * Serialises the generation of a single file.
     *
     * [request] reserves a run, [complete] releases it and reports whether another request arrived in the
     * meantime - in that case exactly one additional run has to be started.
     */
    private class RunState {
        private val lock = Any()
        private var running = false
        private var rerunRequested = false

        /** `true` when the caller has to start the task, `false` when the request was merged. */
        fun request(): Boolean = synchronized(lock) {
            if (running) {
                rerunRequested = true
                false
            } else {
                running = true
                true
            }
        }

        /** `true` when another run has to be started. */
        fun complete(): Boolean = synchronized(lock) {
            running = false
            if (rerunRequested) {
                rerunRequested = false
                running = true
                true
            } else {
                false
            }
        }

        /** Gives a reserved run up again, e.g. when the project or the file is gone. */
        fun release() {
            synchronized(lock) {
                running = false
                rerunRequested = false
            }
        }

        fun isRunning(): Boolean = synchronized(lock) { running }
    }

    /**
     * Called when an `xmake.lua` was written to disk. [file] is expected to be an `xmake.lua` in the
     * given project, but the checks are repeated here so that the method is safe to call from anywhere.
     */
    fun onXMakeLuaChanged(project: Project, file: VirtualFile) {
        if (project.isDisposed || !file.isValid) return
        if (!XMakeLuaFiles.isXMakeLuaFileName(file.name)) return
        if (!shouldHandle(file)) return

        val policy = XMake2CMakeSettings.getInstance().normalized().generationPolicy
        if (policy == GenerationPolicy.DISABLED) return

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || !file.isValid) return@invokeLater
            if (!isConfirmed(project, file, policy)) return@invokeLater
            runGeneration(project, file)
        }
    }

    private fun shouldHandle(file: VirtualFile): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastHandled.put(file.path, now)
        return previous == null || now - previous > COALESCE_WINDOW_MILLIS
    }

    private fun isConfirmed(project: Project, file: VirtualFile, policy: GenerationPolicy): Boolean {
        if (policy != GenerationPolicy.CONFIRM) return true
        if (ApplicationManager.getApplication().isHeadlessEnvironment) {
            LOG.info("Automatic XMake2CMake: automatic generation for ${file.path} skipped in a headless environment")
            return false
        }
        val answer = Messages.showYesNoDialog(
            project,
            "xmake.lua changed:\n${file.path}\n\nGenerate CMakeLists.txt now?",
            "Automatic XMake2CMake",
            Messages.getQuestionIcon()
        )
        return answer == Messages.YES
    }

    /**
     * Explicitly requested update: the "Force Update CMakeLists.txt" action and its keyboard shortcut.
     *
     * Neither the "was changed" gate nor the configured generation policy is applied here - the user
     * asked for the update - but because nothing changed, the request is confirmed once with a
     * dedicated dialog. Repeated requests are throttled and never open a dialog while a generation is
     * running, so pressing Ctrl+S several times in a row cannot pile up windows. Must be called on the EDT.
     */
    fun forceUpdate(project: Project, file: VirtualFile) {
        if (project.isDisposed || !file.isValid) return
        if (!XMakeLuaFiles.isXMakeLuaFileName(file.name)) return

        if (stateOf(file).isRunning()) {
            // Do not interrupt with a dialog while a generation is running: the request is merged into the
            // single additional run of the serialised generation instead.
            LOG.info("Automatic XMake2CMake: a generation for ${file.path} is already running, request merged")
            runGeneration(project, file)
            return
        }

        if (!allowExplicitRequest(file)) return
        if (!isForceUpdateConfirmed(project, file)) return
        generateNow(project, file)
    }

    /**
     * `false` for repeated explicit requests, so that keeping the shortcut pressed does not spam dialogs.
     *
     * Two windows are honoured: repeated explicit requests of this file, and the coalescing window of the
     * automatic (save triggered) path - the latter closes the race between a confirmation dialog that is
     * still being posted and a force update requested right afterwards.
     */
    private fun allowExplicitRequest(file: VirtualFile): Boolean {
        val now = System.currentTimeMillis()
        val previousExplicit = lastExplicitRequest.put(file.path, now)
        val lastAutomatic = lastHandled[file.path]

        val repeated = previousExplicit != null && now - previousExplicit <= EXPLICIT_REQUEST_THROTTLE_MILLIS
        val justHandled = lastAutomatic != null && now - lastAutomatic <= COALESCE_WINDOW_MILLIS
        if (repeated || justHandled) {
            LOG.info(
                "Automatic XMake2CMake: explicit update for ${file.path} skipped " +
                        "(repeated=$repeated, handled ${if (lastAutomatic == null) "-" else now - lastAutomatic} ms ago)"
            )
            return false
        }
        return true
    }

    private fun stateOf(file: VirtualFile): RunState = runStates.computeIfAbsent(file.path) { RunState() }

    /**
     * Runs the validation and the generation immediately: no confirmation dialog and no policy check.
     * Used for updates the user asked for explicitly (menu action / its shortcut); asking the user is the
     * responsibility of the caller. Must be called on the EDT.
     */
    fun generateNow(project: Project, file: VirtualFile) {
        if (project.isDisposed || !file.isValid) return
        if (!XMakeLuaFiles.isXMakeLuaFileName(file.name)) return
        runGeneration(project, file)
    }

    private fun isForceUpdateConfirmed(project: Project, file: VirtualFile): Boolean {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) return false
        val answer = Messages.showYesNoDialog(
            project,
            "xmake.lua has not been modified since the last update:\n${file.path}\n\n" +
                    "Force update CMakeLists.txt anyway?",
            "Force Update CMakeLists.txt",
            Messages.getQuestionIcon()
        )
        return answer == Messages.YES
    }

    /**
     * Starts the generation for [file], serialised per file: while a run is in progress at most one
     * additional run is queued and every further request is merged into it. Must be called on the EDT.
     */
    private fun runGeneration(project: Project, file: VirtualFile) {
        val state = stateOf(file)
        if (!state.request()) {
            LOG.info("Automatic XMake2CMake: generation for ${file.path} merged into the running one")
            return
        }
        startGenerationTask(project, file, state)
    }

    private fun startGenerationTask(project: Project, file: VirtualFile, state: RunState) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating CMakeLists.txt", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = CMakeListsGenerator.generate(project, file, indicator)
                    result.steps.forEach { step ->
                        LOG.info("Automatic XMake2CMake: exit code ${step.exitCode} for ${step.commandLine}")
                    }
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        if (result.success) {
                            XMake2CMakeNotifier.info(project, result.message)
                        } else {
                            XMake2CMakeNotifier.error(project, result.message)
                        }
                    }
                } finally {
                    if (state.complete()) {
                        // Requests that arrived while running are merged into a single additional run.
                        LOG.info("Automatic XMake2CMake: running the merged update for ${file.path}")
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed || !file.isValid) {
                                state.release()
                                return@invokeLater
                            }
                            startGenerationTask(project, file, state)
                        }
                    }
                }
            }
        })
    }
}
