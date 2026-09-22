package io.xymul.xmpp.xmake2cmake.xmake

/**
 * Pure (platform independent) helpers around `xmake.lua` files and xmake command line output.
 * Kept free of IntelliJ Platform types on purpose so that it can be unit tested directly.
 */
object XMakeLuaFiles {

    /** The file name xmake uses for project descriptions. */
    const val XMAKE_LUA_FILE_NAME: String = "xmake.lua"

    private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** `error: .\xmake.lua:2: ')' expected near <eof>` (the path is relative to the working directory). */
    private val XMAKE_LUA_ERROR = Regex("""error:\s*([^\r\n]*xmake\.lua[^\r\n]*)""", RegexOption.IGNORE_CASE)

    private val ANY_ERROR_LINE = Regex("""(?m)^.*\berror:\s*[^\r\n]*$""")

    /** `true` if the given file name is an xmake project description (case insensitive, as xmake does). */
    fun isXMakeLuaFileName(fileName: String): Boolean = fileName.equals(XMAKE_LUA_FILE_NAME, ignoreCase = true)

    /** File name of a file system path (`/tmp/a/xmake.lua` -&gt; `xmake.lua`). */
    fun fileNameOf(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    /** `true` if the given file system path points to an xmake project description. */
    fun isXMakeLuaPath(path: String): Boolean = isXMakeLuaFileName(fileNameOf(path))

    /** Parent directory of a file system path, or the path itself if it has no separator. */
    fun parentDirectoryOf(path: String): String {
        val index = path.indexOfLast { it == '/' || it == '\\' }
        return if (index <= 0) "" else path.substring(0, index)
    }

    /** Removes ANSI escape sequences (xmake prints colored output) from captured process output. */
    fun stripAnsiEscapes(text: String): String = ANSI_ESCAPE.replace(text, "")

    /**
     * Extracts the first xmake error message that refers to an `xmake.lua` file, e.g.
     * `error: .\xmake.lua:2: ')' expected near <eof>`.
     */
    fun findXMakeLuaError(output: String): String? =
        XMAKE_LUA_ERROR.find(stripAnsiEscapes(output))?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Extracts the first error message of any kind from xmake output. */
    fun findAnyError(output: String): String? =
        ANY_ERROR_LINE.find(stripAnsiEscapes(output))?.value?.trim()?.takeIf { it.isNotEmpty() }

    /** Keeps the tail of a (possibly huge) process output so that it fits into a notification. */
    fun tail(text: String, maxLength: Int = 1200): String {
        val clean = stripAnsiEscapes(text).trim()
        return if (clean.length <= maxLength) clean else "..." + clean.substring(clean.length - maxLength)
    }

    /**
     * Splits extra command line arguments typed by the user in the settings page.
     * Quotes are honoured so that arguments containing spaces can be used, while backslashes
     * (e.g. in Windows paths) stay untouched - only `\"` inside a quoted section means a quote.
     */
    fun parseArguments(arguments: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var index = 0
        while (index < arguments.length) {
            val character = arguments[index]
            when {
                quote != null && character == '\\' && index + 1 < arguments.length && arguments[index + 1] == quote -> {
                    current.append(quote)
                    index += 2
                }

                quote != null && character == quote -> {
                    quote = null
                    index++
                }

                quote != null -> {
                    current.append(character)
                    index++
                }

                character == '"' || character == '\'' -> {
                    quote = character
                    index++
                }

                character.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.setLength(0)
                    }
                    index++
                }

                else -> {
                    current.append(character)
                    index++
                }
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }
}
