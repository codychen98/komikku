package eu.kanade.tachiyomi.data.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadRegistryPathTest {

    private val sourceDir = "MangaFire (EN)"
    private val mangaDir = "My level up is strange!_ Reincarnation of a great Man in a Different World"
    private val folderEntry = "Chapter 38_ Promotion – Silver Grade –_a78fa3"
    private val cbzEntry = "unofficial_Ch. 43_1a2b3c.cbz"

    private val cached: Map<String, Set<String>> = mapOf(
        mangaDir to setOf(folderEntry, "unofficial_Ch. 43_1a2b3c"),
    )

    private fun isCached(path: String, dirs: Map<String, Set<String>> = cached): Boolean =
        DownloadRegistryPath.isCached(path) { name -> dirs[name] }

    @Test
    fun `parse splits into three segments and keeps slashes in entry name`() {
        val segments = DownloadRegistryPath.parse("$sourceDir/$mangaDir/$folderEntry")
        assertEquals(
            DownloadRegistryPath.Segments(sourceDir, mangaDir, folderEntry),
            segments,
        )
        assertEquals("a/b", DownloadRegistryPath.parse("s/m/a/b")?.entryName)
    }

    @Test
    fun `parse rejects malformed paths`() {
        assertNull(DownloadRegistryPath.parse("only/two"))
        assertNull(DownloadRegistryPath.parse("/m/entry"))
        assertNull(DownloadRegistryPath.parse("s//entry"))
        assertNull(DownloadRegistryPath.parse(""))
    }

    @Test
    fun `folder entry matches cached chapter dir regardless of chapter name drift`() {
        assertTrue(isCached("$sourceDir/$mangaDir/$folderEntry"))
    }

    @Test
    fun `cbz entry matches cached basename`() {
        assertTrue(isCached("$sourceDir/$mangaDir/$cbzEntry"))
    }

    @Test
    fun `missing chapter entry is not cached`() {
        assertFalse(isCached("$sourceDir/$mangaDir/Chapter 99_000000"))
    }

    @Test
    fun `missing manga dir is not cached`() {
        assertFalse(isCached("$sourceDir/Other Manga/$folderEntry"))
    }

    @Test
    fun `malformed path is not cached`() {
        assertFalse(isCached("$sourceDir/$folderEntry"))
    }
}
