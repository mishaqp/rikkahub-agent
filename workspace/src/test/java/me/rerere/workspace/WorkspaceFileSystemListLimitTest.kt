package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceFileSystemListLimitTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `default limit still truncates to maxListEntries`() {
        val root = tempFolder.newFolder("workspace")
        val fs = WorkspaceFileSystem(WorkspaceConfig(maxListEntries = 3))
        repeat(5) { i -> fs.writeText(root, "file-$i.txt", "x") }
        assertEquals(3, fs.list(root).size)
    }

    @Test
    fun `explicit limit larger than maxListEntries returns every entry`() {
        val root = tempFolder.newFolder("workspace")
        val fs = WorkspaceFileSystem(WorkspaceConfig(maxListEntries = 3))
        repeat(5) { i -> fs.writeText(root, "file-$i.txt", "x") }
        assertEquals(5, fs.list(root, limit = Int.MAX_VALUE).size)
    }

    @Test
    fun `uncapped list keeps order and excludes l2s files`() {
        val root = tempFolder.newFolder("workspace")
        val fs = WorkspaceFileSystem(WorkspaceConfig(maxListEntries = 3))
        fs.writeText(root, "b.txt", "b")
        fs.writeText(root, "a.txt", "a")
        fs.writeText(root, "zdir/inner.txt", "z")
        fs.writeText(root, ".l2s.marker", "hidden")
        val entries = fs.list(root, limit = Int.MAX_VALUE)
        assertEquals(listOf("zdir", "a.txt", "b.txt"), entries.map { it.name })
        assertFalse(entries.any { it.name.startsWith(".l2s.") })
    }
}
