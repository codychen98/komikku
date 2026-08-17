package eu.kanade.domain.chapter.interactor

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.model.ChapterDownload
import tachiyomi.domain.download.repository.ChapterDownloadRepository
import tachiyomi.domain.manga.interactor.GetAllManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import kotlin.test.assertEquals

class ReconcileChapterDownloadsTest {

    private val mangaTitle = "Dungeon Tou de Yadoya wo Yarou"
    private val sourceName = "MangaFire (US)"

    @Test
    fun `reconcile links manga 3607 chapter folders by number without orphan rows`() = runBlocking {
        val manga = Manga.create().copy(
            id = 3607L,
            source = 99L,
            ogTitle = mangaTitle,
        )
        val catalog = (50..61).map { number ->
            catalogChapter(
                id = number.toLong(),
                number = number.toDouble(),
                name = "Ch. $number",
                scanlator = "unofficial",
            )
        }
        val folders = listOf(
            "Chapter 50",
            "Chapter 51",
            "Chapter 52",
            "Chapter 53",
            "Chapter 54",
            "Chapter 55",
            "Chapter 56",
            "Chapter 57",
            "Chapter 58_82c355",
            "Chapter 59_2d920c",
            "Chapter 60_a69da5",
            "unofficial_Ch. 61_c7c283",
        )

        val reconcile = createReconcile(
            manga = manga,
            catalog = catalog,
            folderNames = folders,
            existingDownloads = emptyList(),
        )

        val linked = reconcile.await(mangaIds = listOf(manga.id))

        assertEquals(12, linked)
        coVerify(exactly = 12) { reconcile.chapterDownloadRepository.upsert(any()) }
    }

    @Test
    fun `reconcile skips ambiguous folder matches`() = runBlocking {
        val manga = Manga.create().copy(
            id = 3607L,
            source = 99L,
            ogTitle = mangaTitle,
        )
        val catalog = listOf(
            catalogChapter(id = 1L, number = 58.0, name = "Ch. 58", scanlator = "official"),
            catalogChapter(id = 2L, number = 58.0, name = "Ch. 58", scanlator = "unofficial"),
        )

        val reconcile = createReconcile(
            manga = manga,
            catalog = catalog,
            folderNames = listOf("Chapter 58_82c355"),
            existingDownloads = emptyList(),
        )

        val linked = reconcile.await(mangaIds = listOf(manga.id))

        assertEquals(0, linked)
        coVerify(exactly = 0) { reconcile.chapterDownloadRepository.upsert(any()) }
    }

    @Test
    fun `reconcile skips folders already present in registry`() = runBlocking {
        val manga = Manga.create().copy(
            id = 3607L,
            source = 99L,
            ogTitle = mangaTitle,
        )
        val catalog = listOf(
            catalogChapter(id = 50L, number = 50.0, name = "Ch. 50", scanlator = "unofficial"),
            catalogChapter(id = 51L, number = 51.0, name = "Ch. 51", scanlator = "unofficial"),
        )
        val existing = ChapterDownload(
            chapterId = 50L,
            relativePath = "$sourceName/$mangaTitle/Chapter 50",
            linkedAt = 1L,
        )

        val reconcile = createReconcile(
            manga = manga,
            catalog = catalog,
            folderNames = listOf("Chapter 50", "Chapter 51"),
            existingDownloads = listOf(existing),
        )

        val linked = reconcile.await(mangaIds = listOf(manga.id))

        assertEquals(1, linked)
        val upsertSlot = slot<ChapterDownload>()
        coVerify(exactly = 1) { reconcile.chapterDownloadRepository.upsert(capture(upsertSlot)) }
        assertEquals(51L, upsertSlot.captured.chapterId)
        assertEquals("$sourceName/$mangaTitle/Chapter 51", upsertSlot.captured.relativePath)
    }

    @Test
    fun `reconcile skips tmp dirs and invalid image dirs`() = runBlocking {
        val manga = Manga.create().copy(
            id = 3607L,
            source = 99L,
            ogTitle = mangaTitle,
        )
        val catalog = listOf(
            catalogChapter(id = 50L, number = 50.0, name = "Ch. 50", scanlator = "unofficial"),
        )

        val reconcile = createReconcile(
            manga = manga,
            catalog = catalog,
            folderNames = listOf("Chapter 50_tmp", "Chapter 50"),
            existingDownloads = emptyList(),
            invalidFolderNames = setOf("Chapter 50"),
        )

        val linked = reconcile.await(mangaIds = listOf(manga.id))

        assertEquals(0, linked)
        coVerify(exactly = 0) { reconcile.chapterDownloadRepository.upsert(any()) }
    }

    private fun createReconcile(
        manga: Manga,
        catalog: List<Chapter>,
        folderNames: List<String>,
        existingDownloads: List<ChapterDownload>,
        invalidFolderNames: Set<String> = emptySet(),
    ): TestReconcileHarness {
        val getAllManga = mockk<GetAllManga>()
        val getManga = mockk<GetManga>()
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
        val chapterDownloadRepository = mockk<ChapterDownloadRepository>(relaxed = true)
        val downloadProvider = mockk<DownloadProvider>()
        val sourceManager = mockk<SourceManager>()
        val comicInfoReader = mockk<ChapterDownloadComicInfoReader>()
        val source = mockk<Source>()

        coEvery { getManga.await(manga.id) } returns manga
        coEvery { getChaptersByMangaId.await(manga.id) } returns catalog
        coEvery { chapterDownloadRepository.getByMangaId(manga.id) } returns existingDownloads
        coEvery { sourceManager.getOrStub(manga.source) } returns source
        every { source.id } returns manga.source
        every { downloadProvider.getSourceDirName(source) } returns sourceName
        every { downloadProvider.getMangaDirName(manga.ogTitle) } returns mangaTitle

        val chapterEntries = folderNames.map { name ->
            mockChapterEntry(name)
        }
        val mangaDir = mockk<UniFile>()
        every { mangaDir.listFiles() } returns chapterEntries.toTypedArray()
        every { downloadProvider.findMangaDir(manga.ogTitle, source) } returns mangaDir
        coEvery { comicInfoReader.readComicInfo(any()) } returns null

        chapterEntries.forEach { entry ->
            every {
                downloadProvider.resolveChapterImageDir(entry)
            } answers {
                val entryName = entry.name.orEmpty()
                DownloadProvider.ResolvedChapterImageDir(
                    chapterName = entryName,
                    imageDir = if (entryName in invalidFolderNames) null else mockk(),
                    isValid = entryName !in invalidFolderNames,
                )
            }
        }

        val reconcile = ReconcileChapterDownloads(
            getAllManga = getAllManga,
            getManga = getManga,
            getChaptersByMangaId = getChaptersByMangaId,
            chapterDownloadRepository = chapterDownloadRepository,
            downloadProvider = downloadProvider,
            sourceManager = sourceManager,
            comicInfoReader = comicInfoReader,
        )

        return TestReconcileHarness(reconcile, chapterDownloadRepository)
    }

    private fun mockChapterEntry(name: String): UniFile {
        val entry = mockk<UniFile>()
        every { entry.name } returns name
        every { entry.isDirectory } returns !name.endsWith(".cbz")
        return entry
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

    private data class TestReconcileHarness(
        val reconcile: ReconcileChapterDownloads,
        val chapterDownloadRepository: ChapterDownloadRepository,
    )
}
