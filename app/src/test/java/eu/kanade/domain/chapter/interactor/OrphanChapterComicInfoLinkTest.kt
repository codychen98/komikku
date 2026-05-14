package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.chapter.interactor.OrphanChapterComicInfoLink.ComicInfoWebChapterMatch
import org.junit.jupiter.api.Test
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.chapter.model.Chapter
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OrphanChapterComicInfoLinkTest {

    @Test
    fun `splitComicInfoWebTokens handles comma space and pipe`() {
        val tokens = OrphanChapterComicInfoLink.splitComicInfoWebTokens(
            "https://a.test/one, https://b.test/two | /three",
        )
        assertContentEquals(
            listOf("https://a.test/one", "https://b.test/two", "/three"),
            tokens,
        )
    }

    @Test
    fun `normalizedWebUrlKeys collapses absolute and relative to same key`() {
        val keys = OrphanChapterComicInfoLink.normalizedWebUrlKeys(
            "https://host.example/photo/287058 /photo/287058",
        )
        assertEquals(1, keys.size)
        assertEquals("/photo/287058", keys.single())
    }

    @Test
    fun `match returns Unique when one catalog chapter matches Web`() {
        val comicInfo = ComicInfo(
            title = null,
            series = null,
            number = null,
            summary = null,
            writer = null,
            penciller = null,
            inker = null,
            colorist = null,
            letterer = null,
            coverArtist = null,
            translator = null,
            genre = null,
            tags = null,
            web = ComicInfo.Web("https://example.test/photo/287058"),
            publishingStatus = null,
            categories = null,
            source = null,
            padding = null,
        )
        val catalog = Chapter.create().copy(id = 1L, url = "/photo/287058", name = "Ch1")
        val orphan = Chapter.create().copy(id = 2L, url = "orphaned://x", name = "x")
        val result = OrphanChapterComicInfoLink.matchCatalogChapterFromComicInfo(
            comicInfo,
            listOf(catalog, orphan),
        )
        val unique = assertIs<ComicInfoWebChapterMatch.Unique>(result)
        assertEquals(1L, unique.chapter.id)
    }

    @Test
    fun `match returns Ambiguous when two catalog chapters match`() {
        val comicInfo = ComicInfo(
            title = null,
            series = null,
            number = null,
            summary = null,
            writer = null,
            penciller = null,
            inker = null,
            colorist = null,
            letterer = null,
            coverArtist = null,
            translator = null,
            genre = null,
            tags = null,
            web = ComicInfo.Web("https://a.test/photo/1 https://b.test/other/9"),
            publishingStatus = null,
            categories = null,
            source = null,
            padding = null,
        )
        val c1 = Chapter.create().copy(id = 1L, url = "/photo/1", name = "A")
        val c2 = Chapter.create().copy(id = 2L, url = "/other/9", name = "B")
        val result = OrphanChapterComicInfoLink.matchCatalogChapterFromComicInfo(comicInfo, listOf(c1, c2))
        assertIs<ComicInfoWebChapterMatch.Ambiguous>(result)
    }

    @Test
    fun `match returns None when web empty`() {
        val comicInfo = ComicInfo(
            title = null,
            series = null,
            number = null,
            summary = null,
            writer = null,
            penciller = null,
            inker = null,
            colorist = null,
            letterer = null,
            coverArtist = null,
            translator = null,
            genre = null,
            tags = null,
            web = ComicInfo.Web("   "),
            publishingStatus = null,
            categories = null,
            source = null,
            padding = null,
        )
        val result = OrphanChapterComicInfoLink.matchCatalogChapterFromComicInfo(
            comicInfo,
            listOf(Chapter.create().copy(id = 1L, url = "/photo/1", name = "A")),
        )
        assertIs<ComicInfoWebChapterMatch.None>(result)
    }

    @Test
    fun `orphaned rows are not used as catalog matches`() {
        val comicInfo = ComicInfo(
            title = null,
            series = null,
            number = null,
            summary = null,
            writer = null,
            penciller = null,
            inker = null,
            colorist = null,
            letterer = null,
            coverArtist = null,
            translator = null,
            genre = null,
            tags = null,
            web = ComicInfo.Web("https://example.test/photo/99"),
            publishingStatus = null,
            categories = null,
            source = null,
            padding = null,
        )
        val onlyOrphan = Chapter.create().copy(
            id = 9L,
            url = "orphaned://orph_99",
            name = "orph_99",
        )
    @Test
    fun `mergeOrphanProgressOntoCatalogChapter combines read bookmark and last page`() {
        val catalog = Chapter.create().copy(id = 1L, read = false, bookmark = false, lastPageRead = 2L)
        val orphan = Chapter.create().copy(id = 9L, read = true, bookmark = true, lastPageRead = 5L)
        val u = OrphanChapterComicInfoLink.mergeOrphanProgressOntoCatalogChapter(catalog, orphan)
        assertEquals(1L, u.id)
        assertEquals(true, u.read)
        assertEquals(true, u.bookmark)
        assertEquals(5L, u.lastPageRead)
    }

    @Test
    fun `mergeOrphanProgressOntoCatalogChapter keeps catalog when orphan has no extra progress`() {
        val catalog = Chapter.create().copy(id = 1L, read = true, bookmark = false, lastPageRead = 10L)
        val orphan = Chapter.create().copy(id = 9L, read = false, bookmark = false, lastPageRead = 3L)
        val u = OrphanChapterComicInfoLink.mergeOrphanProgressOntoCatalogChapter(catalog, orphan)
        assertEquals(true, u.read)
        assertEquals(false, u.bookmark)
        assertEquals(10L, u.lastPageRead)
    }
}
