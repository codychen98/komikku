package eu.kanade.tachiyomi.util.chapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChapterHideNonDownloadedDuplicatesTest {

    private data class Item(val id: Long, val number: Double, val downloaded: Boolean)

    private fun List<Item>.filtered(): List<Long> =
        hideNonDownloadedDuplicates(
            chapterNumber = { it.number },
            isDownloaded = { it.downloaded },
        ).map { it.id }

    @Test
    fun `hides non-downloaded versions of a downloaded chapter number`() {
        // Mirrors Reincarnation Coliseum: Akatsuki 37/36/35 downloaded, My Darling 34 downloaded.
        val list = listOf(
            Item(1, 37.0, downloaded = true),
            Item(2, 37.0, downloaded = false),
            Item(3, 36.0, downloaded = true),
            Item(4, 36.0, downloaded = false),
            Item(5, 34.0, downloaded = false),
            Item(6, 34.0, downloaded = true),
        )
        assertEquals(listOf(1L, 3L, 6L), list.filtered())
    }

    @Test
    fun `keeps every version when none of that number is downloaded`() {
        val list = listOf(
            Item(1, 38.0, downloaded = false),
            Item(2, 38.0, downloaded = false),
            Item(3, 33.5, downloaded = false),
        )
        assertEquals(listOf(1L, 2L, 3L), list.filtered())
    }

    @Test
    fun `keeps all downloaded versions of the same number`() {
        val list = listOf(
            Item(1, 10.0, downloaded = true),
            Item(2, 10.0, downloaded = true),
            Item(3, 10.0, downloaded = false),
        )
        assertEquals(listOf(1L, 2L), list.filtered())
    }

    @Test
    fun `never dedupes chapters without a recognised number`() {
        val list = listOf(
            Item(1, -1.0, downloaded = true),
            Item(2, -1.0, downloaded = false),
            Item(3, -1.0, downloaded = false),
        )
        assertEquals(listOf(1L, 2L, 3L), list.filtered())
    }

    @Test
    fun `sub-chapters are distinct numbers and are not hidden by the whole chapter`() {
        val list = listOf(
            Item(1, 33.0, downloaded = true),
            Item(2, 33.5, downloaded = false),
        )
        assertEquals(listOf(1L, 2L), list.filtered())
    }

    @Test
    fun `returns same list when nothing is downloaded`() {
        val list = listOf(Item(1, 1.0, downloaded = false))
        assertEquals(list, list.hideNonDownloadedDuplicates({ it.number }, { it.downloaded }))
    }
}
