package io.xymul.xmpp.xmake2cmake.generation

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.xymul.xmpp.xmake2cmake.settings.XMake2CMakeSettings
import io.xymul.xmpp.xmake2cmake.xmake.XMakeExecutableResolver
import io.xymul.xmpp.xmake2cmake.xmake.XMakeLuaFiles
import java.io.File

/**
 * Validates an `xmake.lua` by asking xmake itself and regenerates `CMakeLists.txt` afterwards.
 *
 * All methods are meant to be called from a background thread.
 */
object CMakeListsGenerator {

    private val LOG: Logger = Logger.getInstance(CMakeListsGenerator::class.java)

    const val CMAKE_LISTS_FILE_NAME: String = "CMakeLists.txt"

    /** One xmake invocation. */
    data class Step(val commandLine: String, val exitCode: Int, val output: String)

    /** Outcome of a validation/generation run. */
    data class Result(
        val success: Boolean,
        val syntaxValid: Boolean,
        val cmakeListsPath: String?,
        val message: String,
        val steps: List<Step>
    )

    fun generate(project: Project, xmakeLuaFile: VirtualFile, indicator: ProgressIndicator): Result {
        val settings = XMake2CMakeSettings.getInstance().normalized()
        val directory = xmakeLuaFile.parent?.path
            ?: return Result(false, false, null, "Cannot determine the directory of ${xmakeLuaFile.path}.", emptyList())

        val executable = XMakeExecutableResolver.resolve(project)
            ?: return Result(
                false,
                false,
                null,
                "No xmake executable found (looked into the settings, the XMake plugin toolkit and the PATH).",
                emptyList()
            )

        val extraArguments = XMakeLuaFiles.parseArguments(settings.extraXmakeArguments)
        val timeoutMillis = settings.timeoutSeconds.coerceAtLeast(1) * 1000

        // Step 1: let xmake check the project description. A syntax error is reported with a non zero
        // exit code and a message like "error: .\xmake.lua:12: ')' expected near <eof>".
        val validation = run(executable.path, listOf("f", "-y") + extraArguments, directory, timeoutMillis)
        if (validation.exitCode != 0) {
            val syntaxError = XMakeLuaFiles.findXMakeLuaError(validation.output)
            val message = if (syntaxError != null) {
                "xmake.lua is not valid, CMakeLists.txt was not touched:\nerror: $syntaxError"
            } else {
                "xmake could not configure the project (not a syntax error), CMakeLists.txt was not touched:\n" +
                        XMakeLuaFiles.tail(validation.output)
            }
            return Result(false, syntaxError != null, null, message, listOf(validation))
        }

        // Step 2: the description is valid, now regenerate CMakeLists.txt next to xmake.lua.
        val generation = run(
            executable.path,
            listOf("project", "-k", "cmake", "-y") + extraArguments,
            directory,
            timeoutMillis
        )
        if (generation.exitCode != 0) {
            return Result(
                false,
                true,
                null,
                "xmake failed to generate $CMAKE_LISTS_FILE_NAME:\n" + XMakeLuaFiles.tail(generation.output),
                listOf(validation, generation)
            )
        }

        val cmakeLists = File(directory, CMAKE_LISTS_FILE_NAME)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(cmakeLists.absolutePath)

        val message = if (cmakeLists.isFile) {
            "CMakeLists.txt updated: ${cmakeLists.absolutePath}"
        } else {
            "$CMAKE_LISTS_FILE_NAME was not created in $directory. Check the xmake output below."
        }
        if (!cmakeLists.isFile) {
            return Result(false, true, null, message, listOf(validation, generation))
        }
        LOG.info("Automatic XMake2CMake: regenerated ${cmakeLists.absolutePath} using ${executable.path} (${executable.source})")
        return Result(true, true, cmakeLists.absolutePath, message, listOf(validation, generation))
    }

    private fun run(
        executablePath: String,
        arguments: List<String>,
        directory: String,
        timeoutMillis: Int
    ): Step {
        val commandLine = GeneralCommandLine(executablePath)
            .withParameters(arguments)
            .withWorkDirectory(directory)
            .withCharset(Charsets.UTF_8)
        return try {
            val output = CapturingProcessHandler(commandLine).runProcess(timeoutMillis)
            Step(
                commandLine.commandLineString,
                output.exitCode,
                XMakeLuaFiles.stripAnsiEscapes(output.stdout + output.stderr)
            )
        } catch (t: Throwable) {
            LOG.warn("Automatic XMake2CMake: failed to run $executablePath", t)
            Step(commandLine.commandLineString, -1, t.message ?: t.toString())
        }
    }
}
