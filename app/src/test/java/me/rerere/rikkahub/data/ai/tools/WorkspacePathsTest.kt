package me.rerere.rikkahub.data.ai.tools

import me.rerere.workspace.WorkspaceLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspacePathsTest {
    private val layout = WorkspaceLayout(
        workspaceDir = "/workspace",
        writableRoots = listOf("/workspace"),
    )

    private fun paths(cwd: String?) = WorkspacePaths(layout, TurnCwdState(cwd))

    @Test
    fun `absolute paths are kept as is`() {
        assertEquals("/workspace/a/b", paths(null).resolve("/workspace/a/b"))
    }

    @Test
    fun `relative paths resolve against the workspace root without cwd`() {
        assertEquals("/workspace/src/Main.kt", paths(null).resolve("src/Main.kt"))
    }

    @Test
    fun `relative paths resolve against the current directory`() {
        assertEquals("/workspace/sub/Main.kt", paths("/workspace/sub").resolve("Main.kt"))
    }

    @Test
    fun `dot dot is normalised`() {
        assertEquals("/workspace/other/Main.kt", paths("/workspace/sub").resolve("../other/Main.kt"))
    }

    @Test
    fun `dot stays in the current directory`() {
        assertEquals("/workspace/sub", paths("/workspace/sub").resolve("."))
    }

    @Test
    fun `absolute paths collapse back to workspace relative form`() {
        assertEquals("src/Main.kt", paths(null).relative("/workspace/src/Main.kt"))
    }

    @Test
    fun `backslashes are treated as separators`() {
        assertEquals("/workspace/src/Main.kt", paths(null).resolve("src\\Main.kt"))
    }
}
