package eu.kanade.domain.chapter.interactor

import org.junit.jupiter.api.Test
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.chapter.model.Chapter
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadFolderMatcherTest {

    private val mangaTitle = "Dungeon Tou de Yadoya wo Yarou"

    @Test
    fun `stripUrlHashSuffix removes trailing hex hash`() {
        assertEquals(
            "Chapter 58",
            DownloadFolderMatcher.stripUrlHashSuffix("Chapter 58_82c355"),
        )
        assertEquals(
            "unofficial_Ch. 61",
            DownloadFolderMatcher.stripUrlHashSuffix("unofficial_Ch. 61_c7c283"),
        )
    }

    @Test
    fun `parseChapterNumberFromFolder parses manga 3607 folder names`() {
        assertEquals(
            58.0,
            DownloadFolderMatcher.parseChapterNumberFromFolder(mangaTitle, "Chapter 58_82c355"),
        )
        assertEquals(
            50.0,
            DownloadFolderMatcher.parseChapterNumberFromFolder(mangaTitle, "Chapter 50"),
        )
        assertEquals(
            61.0,
            DownloadFolderMatcher.parseChapterNumberFromFolder(mangaTitle, "unofficial_Ch. 61_c7c283"),
        )
    }

    @Test
    fun `parseScanlatorPrefix extracts scanlator group`() {
        assertEquals(
            "unofficial",
            DownloadFolderMatcher.parseScanlatorPrefix("unofficial_Ch. 61_c7c283"),
        )
        assertNull(DownloadFolderMatcher.parseScanlatorPrefix("Chapter 58_82c355"))
    }

    @Test
    fun `matchCatalogChapter disambiguates official and unofficial by scanlator prefix`() {
        val catalog = listOf(
            catalogChapter(id = 1L, number = 58.0, name = "Ch. 58", scanlator = "official"),
            catalogChapter(id = 2L, number = 58.0, name = "Ch. 58", scanlator = "unofficial"),
        )

        assertEquals(
            1L,
            DownloadFolderMatcher.matchCatalogChapter(
                catalogChapters = catalog,
                folderName = "official_Ch. 58_abc123",
                mangaTitle = mangaTitle,
            )?.id,
        )
        assertEquals(
            2L,
            DownloadFolderMatcher.matchCatalogChapter(
                catalogChapters = catalog,
                folderName = "unofficial_Ch. 58_xyz789",
                mangaTitle = mangaTitle,
            )?.id,
        )
    }

    @Test
    fun `matchCatalogChapter returns null when same number is ambiguous without scanlator`() {
        val catalog = listOf(
            catalogChapter(id = 1L, number = 58.0, name = "Ch. 58", scanlator = "official"),
            catalogChapter(id = 2L, number = 58.0, name = "Ch. 58", scanlator = "unofficial"),
        )

        assertNull(
            DownloadFolderMatcher.matchCatalogChapter(
                catalogChapters = catalog,
                folderName = "Chapter 58_82c355",
                mangaTitle = mangaTitle,
            ),
        )
    }

    @Test
    fun `matchCatalogChapter returns null when no catalog chapter matches`() {
        val catalog = listOf(
            catalogChapter(id = 1L, number = 50.0, name = "Ch. 50", scanlator = "unofficial"),
        )

        assertNull(
            DownloadFolderMatcher.matchCatalogChapter(
                catalogChapters = catalog,
                folderName = "Chapter 99_abc123",
                mangaTitle = mangaTitle,
            ),
        )
    }

    @Test
    fun `matchCatalogChapter links chapter 50 folder by number when unique`() {
        val catalog = (50..60).map { number ->
            catalogChapter(
                id = number.toLong(),
                number = number.toDouble(),
                name = "Ch. $number",
                scanlator = "unofficial",
            )
        }

        assertEquals(
            58L,
            DownloadFolderMatcher.matchCatalogChapter(
                catalogChapters = catalog,
                folderName = "Chapter 58_82c355",
                mangaTitle = mangaTitle,
            )?.id,
        )
    }

    @Test
    fun `matchCatalogChapter uses ComicInfo number and translator`() {
        val catalog = listOf(
            catalogChapter(id = 1L, number = 61.0, name = "Ch. 61", scanlator = "official"),
            catalogChapter(id = 2L, number = 61.0, name = "Ch. 61", scanlator = "unofficial"),
        )
        val comicInfo = ComicInfo(
            title = null,
            series = null,
            number = ComicInfo.Number("61"),
            summary = null,
            writer = null,
            penciller = null,
            inker = null,
            colorist = null,
            letterer = null,
            coverArtist = null,
            translator = ComicInfo.Translator("unofficial"),
            genre = null,
            tags = null,
            web = null,
            publishingStatus = null,
            categories = null,
            source = null,
            padding = null,
        )

        assertEquals(
            2L,
            DownloadFolderMatcher.matchCatalogChapter(
                catalogChapters = catalog,
                folderName = "some_folder_name",
                mangaTitle = mangaTitle,
                comicInfo = comicInfo,
            )?.id,
        )
    }

    private fun catalogChapter(
        id: Long,
        number: Double,
        name: String,
        scanlator: String?,
    ): Chapter = Chapter.create().copy(
        id = id,
        chapterNumber = number,
        name = name,
        scanlator = scanlator,
        url = "/manga/example/chapter-$number/",
    )
}
