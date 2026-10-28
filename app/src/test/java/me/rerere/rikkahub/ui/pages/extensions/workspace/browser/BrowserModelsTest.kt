package me.rerere.rikkahub.ui.pages.extensions.workspace.browser

import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserModelsTest {

    private fun file(path: String, size: Long = 0, updatedAt: Long = 0) = WorkspaceFileEntry(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        sizeBytes = size,
        updatedAt = updatedAt,
    )

    private fun dir(path: String) = WorkspaceFileEntry(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = true,
        sizeBytes = 0,
        updatedAt = 0,
    )

    @Test
    fun `breadcrumbs are cumulative relative to the area root`() {
        val trail = crumbTrail("a/b/c")
        assertEquals(listOf("a", "a/b", "a/b/c"), trail.map { it.path })
        assertEquals(listOf("a", "b", "c"), trail.map { it.label })
    }

    @Test
    fun `empty path has no crumbs, the area chip is the root`() {
        assertEquals(emptyList<Crumb>(), crumbTrail(""))
        assertEquals(emptyList<Crumb>(), crumbTrail("/"))
    }

    @Test
    fun `absolute paths keep the leading slash in crumb targets`() {
        val trail = crumbTrail("/data/data/com.termux/files/home")
        assertEquals("/data", trail.first().path)
        assertEquals("/data/data/com.termux/files/home", trail.last().path)
        assertEquals("home", trail.last().label)
    }

    @Test
    fun `directories always sort before files`() {
        val view = listOf(file("z.kt"), dir("zdir"), file("a.kt")).browserView(
            query = "",
            sort = BrowserSort.NAME,
            ascending = true,
        )
        assertEquals(listOf("zdir", "a.kt", "z.kt"), view.map { it.name })
    }

    @Test
    fun `descending flips only the sort key, not the directory grouping`() {
        val view = listOf(file("a.kt", size = 1), file("b.kt", size = 9), dir("d")).browserView(
            query = "",
            sort = BrowserSort.SIZE,
            ascending = false,
        )
        assertEquals(listOf("d", "b.kt", "a.kt"), view.map { it.name })
    }

    @Test
    fun `query matches name and path case-insensitively`() {
        val entries = listOf(dir("src"), file("src/Main.kt"), file("README.md"))
        assertEquals(
            listOf("src/Main.kt"),
            entries.browserView(query = "main", sort = BrowserSort.NAME, ascending = true).map { it.path },
        )
        assertEquals(
            listOf("src/Main.kt"),
            entries.browserView(query = "SRC/main", sort = BrowserSort.NAME, ascending = true).map { it.path },
        )
    }

    @Test
    fun `only changed keeps directories and filters files by absolute path`() {
        val entries = listOf(dir("src"), file("src/A.kt"), file("src/B.kt"))
        val changed = setOf("/ws/src/B.kt")
        val view = entries.browserView(
            query = "",
            sort = BrowserSort.NAME,
            ascending = true,
            onlyChanged = changed,
            absolutePath = { entry -> "/ws/${entry.path}" },
        )
        assertEquals(listOf("src", "src/B.kt"), view.map { it.path })
    }

    @Test
    fun `only changed falls back to relative paths when no layout is available`() {
        val entries = listOf(file("a.kt"), file("b.kt"))
        val view = entries.browserView(
            query = "",
            sort = BrowserSort.NAME,
            ascending = true,
            onlyChanged = setOf("b.kt"),
            absolutePath = { null },
        )
        assertEquals(listOf("b.kt"), view.map { it.path })
    }
}
