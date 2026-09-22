package io.xymul.xmpp.xmake2cmake.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * What has to happen when an `xmake.lua` was modified (Ctrl+S or closing its editor tab).
 */
enum class GenerationPolicy {
    /** Ask the user before every automatic generation. */
    CONFIRM,

    /** Regenerate `CMakeLists.txt` without any confirmation. */
    AUTO,

    /** Do nothing at all (automatic generation is switched off). */
    DISABLED,
}

/**
 * Application wide settings of the Automatic XMake2CMake plugin.
 *
 * Stored in the IDE configuration directory (`options/automaticXMake2CMake.xml`), so it is
 * available in every project.
 */
@Service(Service.Level.APP)
@State(name = "AutomaticXMake2CMake", storages = [Storage("automaticXMake2CMake.xml")])
class XMake2CMakeSettings : PersistentStateComponent<XMake2CMakeSettings.SettingsState> {

    /** Serialized state, see [XMake2CMakeSettings]. */
    class SettingsState {
        /** One of [GenerationPolicy], defaults to "ask before every generation". */
        var generationPolicy: GenerationPolicy = GenerationPolicy.CONFIRM

        /**
         * Optional explicit path of the `xmake` executable. When empty the executable configured in
         * the XMake plugin toolkit is used, with a fall back to the `PATH`.
         */
        var xmakeExecutablePath: String = ""

        /** Extra arguments appended to both the validation and the generation xmake command. */
        var extraXmakeArguments: String = ""

        /** Maximum time (seconds) a single xmake invocation may take. */
        var timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS
    }

    private var currentState = SettingsState()

    override fun getState(): SettingsState = currentState

    override fun loadState(state: SettingsState) {
        currentState = state
    }

    /** Makes sure that states written by older/hand edited files are usable. */
    fun normalized(): SettingsState {
        val state = currentState
        if (state.timeoutSeconds <= 0) {
            state.timeoutSeconds = DEFAULT_TIMEOUT_SECONDS
        }
        return state
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS: Int = 300

        fun getInstance(): XMake2CMakeSettings =
            ApplicationManager.getApplication().getService(XMake2CMakeSettings::class.java)
    }
}
