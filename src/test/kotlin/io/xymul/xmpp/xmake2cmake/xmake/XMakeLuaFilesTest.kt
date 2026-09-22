package io.xymul.xmpp.xmake2cmake.xmake

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the platform independent helpers of the plugin.
 */
class XMakeLuaFilesTest {

    @Test
    fun `recognizes xmake lua file names`() {
        assertTrue(XMakeLuaFiles.isXMakeLuaFileName("xmake.lua"))
        assertTrue(XMakeLuaFiles.isXMakeLuaFileName("XMake.Lua"))
        assertFalse(XMakeLuaFiles.isXMakeLuaFileName("xmake.lua.bak"))
        assertFalse(XMakeLuaFiles.isXMakeLuaFileName("cmake.lua"))
        assertFalse(XMakeLuaFiles.isXMakeLuaFileName(""))
    }

    @Test
    fun `recognizes xmake lua paths in any sub directory`() {
        assertTrue(XMakeLuaFiles.isXMakeLuaPath("D:\\project\\xmake.lua"))
        assertTrue(XMakeLuaFiles.isXMakeLuaPath("/home/user/project/sub/dir/xmake.lua"))
        assertFalse(XMakeLuaFiles.isXMakeLuaPath("/home/user/project/sub/dir/CMakeLists.txt"))
    }

    @Test
    fun `extracts file name and parent directory`() {
        assertEquals("xmake.lua", XMakeLuaFiles.fileNameOf("D:\\project\\sub\\xmake.lua"))
        assertEquals("D:\\project\\sub", XMakeLuaFiles.parentDirectoryOf("D:\\project\\sub\\xmake.lua"))
        assertEquals("/home/user/project", XMakeLuaFiles.parentDirectoryOf("/home/user/project/xmake.lua"))
        assertEquals("", XMakeLuaFiles.parentDirectoryOf("xmake.lua"))
    }

    @Test
    fun `strips ansi escape sequences`() {
        val colored = "\u001B[0m\u001B[1;31;1merror: \u001B[0m.\\xmake.lua:2: ')' expected near <eof>\u001B[0m"
        assertEquals("error: .\\xmake.lua:2: ')' expected near <eof>", XMakeLuaFiles.stripAnsiEscapes(colored))
    }

    @Test
    fun `finds syntax errors reported by xmake`() {
        val output = "\u001B[1;31;1merror: \u001B[0m.\\xmake.lua:2: ')' expected near <eof>\u001B[0m"
        assertEquals(".\\xmake.lua:2: ')' expected near <eof>", XMakeLuaFiles.findXMakeLuaError(output))
    }

    @Test
    fun `does not report syntax errors for other failures`() {
        assertNull(XMakeLuaFiles.findXMakeLuaError("error: cannot find any c++ compiler"))
        assertEquals("error: cannot find any c++ compiler", XMakeLuaFiles.findAnyError("error: cannot find any c++ compiler"))
    }

    @Test
    fun `keeps the tail of long output`() {
        val output = "a".repeat(2000)
        val tail = XMakeLuaFiles.tail(output, 100)
        assertEquals(103, tail.length)
        assertTrue(tail.startsWith("..."))
    }

    @Test
    fun `parses extra command line arguments`() {
        assertEquals(emptyList(), XMakeLuaFiles.parseArguments("   "))
        assertEquals(listOf("-p", "windows", "-a", "x64"), XMakeLuaFiles.parseArguments("-p windows -a x64"))
        assertEquals(
            listOf("--ndk=C:\\Program Files\\ndk", "-m", "debug"),
            XMakeLuaFiles.parseArguments("--ndk=\"C:\\Program Files\\ndk\" -m debug")
        )
    }
}
