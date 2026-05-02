package eu.kanade.tachiyomi.util.chapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ChapterRemoveDuplicatesTest {

    @Test
    fun `unreadSkippedSubChapterDuplicates whole and one fraction same floor`() {
        val whole = ch(id = 1, num = 46.0, read = false)
        val fraction = ch(id = 2, num = 46.5, read = false)
        val list = listOf(whole, fraction)
        assertEquals(listOf(fraction), list.unreadSkippedSubChapterDuplicates())
    }

    @Test
    fun `unreadSkippedSubChapterDuplicates excludes read fractions`() {
        val whole = ch(id = 1, num = 46.0, read = false)
        val fraction = ch(id = 2, num = 46.5, read = true)
        val list = listOf(whole, fraction)
        assertTrue(list.unreadSkippedSubChapterDuplicates().isEmpty())
    }

    @Test
    fun `unreadSkippedSubChapterDuplicates multiple fractions same floor`() {
        val whole = ch(id = 1, num = 10.0, read = false)
        val a = ch(id = 2, num = 10.1, read = false)
        val b = ch(id = 3, num = 10.9, read = false)
        val list = listOf(whole, a, b)
        assertEquals(setOf(2L, 3L), list.unreadSkippedSubChapterDuplicates().map { it.id }.toSet())
    }

    @Test
    fun `unreadSkippedSubChapterDuplicates fraction only no whole kept visible`() {
        val onlyFraction = ch(id = 1, num = 46.5, read = false)
        val list = listOf(onlyFraction)
        assertTrue(list.unreadSkippedSubChapterDuplicates().isEmpty())
        assertEquals(list, list.removeSubChapterDuplicates())
    }

    @Test
    fun `unreadSkippedSubChapterDuplicates negative chapter numbers never skipped`() {
        val neg = ch(id = 1, num = -1.0, read = false)
        val frac = ch(id = 2, num = 46.5, read = false)
        val list = listOf(neg, frac)
        assertTrue(list.unreadSkippedSubChapterDuplicates().isEmpty())
    }

    @Test
    fun `unreadSkippedSubChapterDuplicates matches removeSubChapterDuplicates hidden unread`() {
        val chapters = listOf(
            ch(id = 1, num = -1.0, read = false),
            ch(id = 2, num = 0.0, read = false),
            ch(id = 3, num = 0.5, read = false),
            ch(id = 4, num = 1.0, read = true),
            ch(id = 5, num = 1.2, read = false),
            ch(id = 6, num = 1.4, read = true),
            ch(id = 7, num = 2.5, read = false),
        )
        val visible = chapters.removeSubChapterDuplicates()
        val hiddenIds = chapters.map { it.id }.toSet() - visible.map { it.id }.toSet()
        val skippedUnreadIds = chapters.unreadSkippedSubChapterDuplicates().map { it.id }.toSet()
        val expectedHiddenUnread = chapters
            .filter { it.id in hiddenIds && !it.read }
            .map { it.id }
            .toSet()
        assertEquals(expectedHiddenUnread, skippedUnreadIds)
    }

    private fun ch(
        id: Long,
        num: Double,
        read: Boolean,
    ): Chapter {
        return Chapter.create().copy(
            id = id,
            mangaId = 100L,
            read = read,
            chapterNumber = num,
        )
    }
}
