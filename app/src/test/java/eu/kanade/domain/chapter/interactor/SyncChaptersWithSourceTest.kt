package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.core.common.preference.Preference
import kotlin.test.assertTrue

class SyncChaptersWithSourceTest {

    @Test
    fun `downloaded removed unread chapters are marked read without deleting`() = runBlocking {
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapterRepository = mockk<ChapterRepository>()
        val updateManga = mockk<UpdateManga>()
        val updateChapter = mockk<UpdateChapter>()
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
        val getExcludedScanlators = mockk<GetExcludedScanlators>()
        val libraryPreferences = mockk<LibraryPreferences>()
        val markDuplicatePreference = mockk<Preference<Set<String>>>()

        val unreadDownloadedRemoved = chapter(id = 1, read = false, lastPageRead = 7, url = "/chapter-1")
        val readDownloadedRemoved = chapter(id = 2, read = true, lastPageRead = 9, url = "/chapter-2")
        val dbChapters = listOf(unreadDownloadedRemoved, readDownloadedRemoved)
        val manga = Manga.create().copy(id = 10, source = 0L, ogTitle = "Test Manga")

        coEvery { getChaptersByMangaId.await(manga.id) } returns dbChapters
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any(), any()) } returns true
        coEvery { chapterRepository.removeChaptersWithIds(any()) } returns Unit
        coEvery { chapterRepository.addAll(any()) } answers { firstArg() }
        coEvery { updateChapter.awaitAll(any()) } returns Unit
        coEvery { updateManga.awaitUpdateFetchInterval(any(), any(), any()) } returns true
        coEvery { updateManga.awaitUpdateLastUpdate(any()) } returns true
        coEvery { getExcludedScanlators.await(manga.id) } returns emptyList()
        every { libraryPreferences.markDuplicateReadChapterAsRead() } returns markDuplicatePreference
        every { markDuplicatePreference.get() } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            updateChapter = updateChapter,
            getChaptersByMangaId = getChaptersByMangaId,
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )

        sync.await(
            rawSourceChapters = emptyList(),
            manga = manga,
            source = localSource(),
            manualFetch = false,
        )

        coVerify(exactly = 0) { chapterRepository.removeChaptersWithIds(any()) }
        coVerify(exactly = 1) {
            updateChapter.awaitAll(
                match { updates ->
                    updates.size == 1 &&
                        updates.first().id == unreadDownloadedRemoved.id &&
                        updates.first().read == true &&
                        updates.first().lastPageRead == null
                },
            )
        }
    }

    @Test
    fun `downloaded chapter with changed source url should be reconciled instead of duplicated`() = runBlocking {
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapterRepository = mockk<ChapterRepository>()
        val updateManga = mockk<UpdateManga>()
        val updateChapter = mockk<UpdateChapter>()
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
        val getExcludedScanlators = mockk<GetExcludedScanlators>()
        val libraryPreferences = mockk<LibraryPreferences>()
        val markDuplicatePreference = mockk<Preference<Set<String>>>()

        val existingDownloadedChapter = Chapter.create().copy(
            id = 11,
            mangaId = 10,
            read = false,
            lastPageRead = 12,
            chapterNumber = 12.0,
            name = "Chapter 12 (old naming)",
            url = "/chapter-12-old",
        )
        val dbChapters = listOf(existingDownloadedChapter)
        val manga = Manga.create().copy(id = 10, source = 0L, ogTitle = "Test Manga", title = "Test Manga")
        val sourceChapter = SChapter.create().apply {
            name = "Ch. 12"
            url = "/chapter-12-new"
            chapter_number = 12f
        }

        coEvery { getChaptersByMangaId.await(manga.id) } returns dbChapters
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any(), any()) } returns true
        coEvery { chapterRepository.removeChaptersWithIds(any()) } returns Unit
        coEvery { chapterRepository.addAll(any()) } answers { firstArg() }
        coEvery { updateChapter.awaitAll(any()) } returns Unit
        coEvery { updateManga.awaitUpdateFetchInterval(any(), any(), any()) } returns true
        coEvery { updateManga.awaitUpdateLastUpdate(any()) } returns true
        coEvery { getExcludedScanlators.await(manga.id) } returns emptyList()
        every { libraryPreferences.markDuplicateReadChapterAsRead() } returns markDuplicatePreference
        every { markDuplicatePreference.get() } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            updateChapter = updateChapter,
            getChaptersByMangaId = getChaptersByMangaId,
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )

        sync.await(
            rawSourceChapters = listOf(sourceChapter),
            manga = manga,
            source = localSource(),
            manualFetch = false,
        )

        coVerify(exactly = 1) { chapterRepository.removeChaptersWithIds(match { 11L in it }) }
    }

    @Test
    fun `downloaded chapter should reconcile when source returns both old and new urls`() = runBlocking {
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapterRepository = mockk<ChapterRepository>()
        val updateManga = mockk<UpdateManga>()
        val updateChapter = mockk<UpdateChapter>()
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
        val getExcludedScanlators = mockk<GetExcludedScanlators>()
        val libraryPreferences = mockk<LibraryPreferences>()
        val markDuplicatePreference = mockk<Preference<Set<String>>>()

        val existingDownloadedChapter = Chapter.create().copy(
            id = 71,
            mangaId = 10,
            read = true,
            bookmark = true,
            lastPageRead = 16,
            chapterNumber = 5.2,
            name = "Chapter 5.2_ When it rains (Part 2)_02200e",
            url = "/chapter-5-2-old",
        )
        val manga = Manga.create().copy(id = 10, source = 0L, ogTitle = "Test Manga", title = "Test Manga")
        val oldSourceChapter = SChapter.create().apply {
            name = "Ch. 5.2 - When it rains (Part 2)"
            url = "/chapter-5-2-old"
            chapter_number = 5.2f
        }
        val newSourceChapter = SChapter.create().apply {
            name = "Ch. 5.2 - When it rains (Part 2)"
            url = "/chapter-5-2-new"
            chapter_number = 5.2f
        }

        coEvery { getChaptersByMangaId.await(manga.id) } returns listOf(existingDownloadedChapter)
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any(), any()) } returns true
        coEvery { downloadManager.renameChapter(any(), any(), any(), any()) } returns Unit
        coEvery { chapterRepository.removeChaptersWithIds(any()) } returns Unit
        coEvery { chapterRepository.addAll(any()) } answers { firstArg() }
        coEvery { updateChapter.awaitAll(any()) } returns Unit
        coEvery { updateManga.awaitUpdateFetchInterval(any(), any(), any()) } returns true
        coEvery { updateManga.awaitUpdateLastUpdate(any()) } returns true
        coEvery { getExcludedScanlators.await(manga.id) } returns emptyList()
        every { libraryPreferences.markDuplicateReadChapterAsRead() } returns markDuplicatePreference
        every { markDuplicatePreference.get() } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            updateChapter = updateChapter,
            getChaptersByMangaId = getChaptersByMangaId,
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )

        sync.await(
            rawSourceChapters = listOf(oldSourceChapter, newSourceChapter),
            manga = manga,
            source = localSource(),
            manualFetch = false,
        )

        coVerify(exactly = 1) { chapterRepository.removeChaptersWithIds(match { 71L in it }) }
        coVerify(exactly = 1) {
            chapterRepository.addAll(
                match { added ->
                    added.size == 1 &&
                        added.first().url == "/chapter-5-2-new" &&
                        added.first().read &&
                        added.first().bookmark
                },
            )
        }
    }

    @Test
    fun `decimal chapters should not cross-reconcile between different subchapter numbers`() = runBlocking {
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapterRepository = mockk<ChapterRepository>()
        val updateManga = mockk<UpdateManga>()
        val updateChapter = mockk<UpdateChapter>()
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
        val getExcludedScanlators = mockk<GetExcludedScanlators>()
        val libraryPreferences = mockk<LibraryPreferences>()
        val markDuplicatePreference = mockk<Preference<Set<String>>>()

        val existingDownloadedChapter = Chapter.create().copy(
            id = 81,
            mangaId = 10,
            read = true,
            bookmark = true,
            lastPageRead = 14,
            chapterNumber = 5.1,
            name = "Chapter 5.1_ When it Rains (Part 1)_70d265",
            url = "/chapter-5-1-old",
        )
        val manga = Manga.create().copy(id = 10, source = 0L, ogTitle = "Test Manga", title = "Test Manga")
        val oldSourceChapter = SChapter.create().apply {
            name = "Ch. 5.1 - When it Rains (Part 1)"
            url = "/chapter-5-1-old"
            chapter_number = 5.1f
        }
        val newSourceChapter = SChapter.create().apply {
            name = "Ch. 5.2 - When it rains (Part 2)"
            url = "/chapter-5-2-new"
            chapter_number = 5.2f
        }

        coEvery { getChaptersByMangaId.await(manga.id) } returns listOf(existingDownloadedChapter)
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any(), any()) } returns true
        coEvery { downloadManager.renameChapter(any(), any(), any(), any()) } returns Unit
        coEvery { chapterRepository.removeChaptersWithIds(any()) } returns Unit
        coEvery { chapterRepository.addAll(any()) } answers { firstArg() }
        coEvery { updateChapter.awaitAll(any()) } returns Unit
        coEvery { updateManga.awaitUpdateFetchInterval(any(), any(), any()) } returns true
        coEvery { updateManga.awaitUpdateLastUpdate(any()) } returns true
        coEvery { getExcludedScanlators.await(manga.id) } returns emptyList()
        every { libraryPreferences.markDuplicateReadChapterAsRead() } returns markDuplicatePreference
        every { markDuplicatePreference.get() } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            updateChapter = updateChapter,
            getChaptersByMangaId = getChaptersByMangaId,
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )

        sync.await(
            rawSourceChapters = listOf(oldSourceChapter, newSourceChapter),
            manga = manga,
            source = localSource(),
            manualFetch = false,
        )

        coVerify(exactly = 0) { chapterRepository.removeChaptersWithIds(match { 81L in it }) }
        coVerify(exactly = 1) {
            chapterRepository.addAll(
                match { added ->
                    added.size == 1 &&
                        added.first().url == "/chapter-5-2-new" &&
                        !added.first().read &&
                        !added.first().bookmark
                },
            )
        }
    }

    @Test
    fun `same chapter number with different scanlator should not reconcile downloaded chapter`() = runBlocking {
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapterRepository = mockk<ChapterRepository>()
        val updateManga = mockk<UpdateManga>()
        val updateChapter = mockk<UpdateChapter>()
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
        val getExcludedScanlators = mockk<GetExcludedScanlators>()
        val libraryPreferences = mockk<LibraryPreferences>()
        val markDuplicatePreference = mockk<Preference<Set<String>>>()

        val existingDownloadedChapter = Chapter.create().copy(
            id = 21,
            mangaId = 10,
            read = false,
            chapterNumber = 12.0,
            name = "Chapter 12",
            scanlator = "GroupA",
            url = "/chapter-12-old",
        )
        val manga = Manga.create().copy(id = 10, source = 0L, ogTitle = "Test Manga", title = "Test Manga")
        val sourceChapter = SChapter.create().apply {
            name = "Chapter 12"
            url = "/chapter-12-new"
            chapter_number = 12f
            scanlator = "GroupB"
        }

        coEvery { getChaptersByMangaId.await(manga.id) } returns listOf(existingDownloadedChapter)
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any(), any()) } returns true
        coEvery { chapterRepository.removeChaptersWithIds(any()) } returns Unit
        coEvery { chapterRepository.addAll(any()) } answers { firstArg() }
        coEvery { updateChapter.awaitAll(any()) } returns Unit
        coEvery { updateManga.awaitUpdateFetchInterval(any(), any(), any()) } returns true
        coEvery { updateManga.awaitUpdateLastUpdate(any()) } returns true
        coEvery { getExcludedScanlators.await(manga.id) } returns emptyList()
        every { libraryPreferences.markDuplicateReadChapterAsRead() } returns markDuplicatePreference
        every { markDuplicatePreference.get() } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            updateChapter = updateChapter,
            getChaptersByMangaId = getChaptersByMangaId,
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )

        sync.await(
            rawSourceChapters = listOf(sourceChapter),
            manga = manga,
            source = localSource(),
            manualFetch = false,
        )

        coVerify(exactly = 0) { chapterRepository.removeChaptersWithIds(match { 21L in it }) }
    }

    @Test
    fun `reconciled chapter keeps read state while other downloaded removed chapter is auto-marked read`() = runBlocking {
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapterRepository = mockk<ChapterRepository>()
        val updateManga = mockk<UpdateManga>()
        val updateChapter = mockk<UpdateChapter>()
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
        val getExcludedScanlators = mockk<GetExcludedScanlators>()
        val libraryPreferences = mockk<LibraryPreferences>()
        val markDuplicatePreference = mockk<Preference<Set<String>>>()

        val reconciledCandidate = Chapter.create().copy(
            id = 31,
            mangaId = 10,
            read = true,
            chapterNumber = 12.0,
            name = "Chapter 12 old",
            url = "/chapter-12-old",
        )
        val downloadedRemovedUnread = Chapter.create().copy(
            id = 32,
            mangaId = 10,
            read = false,
            chapterNumber = 50.0,
            name = "Chapter 50",
            url = "/chapter-50",
        )
        val manga = Manga.create().copy(id = 10, source = 0L, ogTitle = "Test Manga", title = "Test Manga")
        val sourceChapter = SChapter.create().apply {
            name = "Ch. 12"
            url = "/chapter-12-new"
            chapter_number = 12f
        }

        coEvery { getChaptersByMangaId.await(manga.id) } returns listOf(reconciledCandidate, downloadedRemovedUnread)
        every {
            downloadManager.isChapterDownloaded(
                chapterName = any(),
                chapterScanlator = any(),
                chapterUrl = any(),
                mangaTitle = any(),
                sourceId = any(),
            )
        } answers {
            val chapterUrl = arg<String>(2)
            chapterUrl == "/chapter-12-old" || chapterUrl == "/chapter-50"
        }
        coEvery { chapterRepository.removeChaptersWithIds(any()) } returns Unit
        coEvery { chapterRepository.addAll(any()) } answers { firstArg() }
        coEvery { updateChapter.awaitAll(any()) } returns Unit
        coEvery { updateManga.awaitUpdateFetchInterval(any(), any(), any()) } returns true
        coEvery { updateManga.awaitUpdateLastUpdate(any()) } returns true
        coEvery { getExcludedScanlators.await(manga.id) } returns emptyList()
        every { libraryPreferences.markDuplicateReadChapterAsRead() } returns markDuplicatePreference
        every { markDuplicatePreference.get() } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            updateChapter = updateChapter,
            getChaptersByMangaId = getChaptersByMangaId,
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )

        sync.await(
            rawSourceChapters = listOf(sourceChapter),
            manga = manga,
            source = localSource(),
            manualFetch = false,
        )

        coVerify(exactly = 1) {
            chapterRepository.addAll(
                match { added ->
                    added.size == 1 &&
                        added.first().url == "/chapter-12-new" &&
                        added.first().read
                },
            )
        }
        coVerify(exactly = 1) {
            updateChapter.awaitAll(
                match { updates ->
                    updates.size == 1 &&
                        updates.first().id == downloadedRemovedUnread.id &&
                        updates.first().read == true
                },
            )
        }
    }

    @Test
    fun `orphaned hash-suffixed chapter should reconcile with fetched chapter when numbers are parsed from names`() = runBlocking {
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapterRepository = mockk<ChapterRepository>()
        val updateManga = mockk<UpdateManga>()
        val updateChapter = mockk<UpdateChapter>()
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
        val getExcludedScanlators = mockk<GetExcludedScanlators>()
        val libraryPreferences = mockk<LibraryPreferences>()
        val markDuplicatePreference = mockk<Preference<Set<String>>>()

        val orphanedDownloadedChapter = Chapter.create().copy(
            id = 41,
            mangaId = 10,
            read = true,
            lastPageRead = 8,
            chapterNumber = -1.0,
            name = "Chapter 11_ The Point Of No Return_b1f4ca",
            url = "orphaned://Chapter 11_ The Point Of No Return_b1f4ca",
        )
        val manga = Manga.create().copy(id = 10, source = 0L, ogTitle = "Test Manga", title = "Test Manga")
        val sourceChapter = SChapter.create().apply {
            name = "Ch. 11 - The Point Of No Return"
            url = "/chapter-11-new"
            chapter_number = 11f
        }

        coEvery { getChaptersByMangaId.await(manga.id) } returns listOf(orphanedDownloadedChapter)
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any(), any()) } returns false
        coEvery { chapterRepository.removeChaptersWithIds(any()) } returns Unit
        coEvery { chapterRepository.addAll(any()) } answers { firstArg() }
        coEvery { updateChapter.awaitAll(any()) } returns Unit
        coEvery { updateManga.awaitUpdateFetchInterval(any(), any(), any()) } returns true
        coEvery { updateManga.awaitUpdateLastUpdate(any()) } returns true
        coEvery { getExcludedScanlators.await(manga.id) } returns emptyList()
        every { libraryPreferences.markDuplicateReadChapterAsRead() } returns markDuplicatePreference
        every { markDuplicatePreference.get() } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            updateChapter = updateChapter,
            getChaptersByMangaId = getChaptersByMangaId,
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )

        sync.await(
            rawSourceChapters = listOf(sourceChapter),
            manga = manga,
            source = localSource(),
            manualFetch = false,
        )

        coVerify(exactly = 1) { chapterRepository.removeChaptersWithIds(match { 41L in it }) }
        coVerify(exactly = 1) {
            chapterRepository.addAll(
                match { added ->
                    added.size == 1 &&
                        added.first().url == "/chapter-11-new" &&
                        added.first().read
                },
            )
        }
    }

    @Test
    fun `downloaded stale chapter duplicate should merge into existing canonical source chapter`() = runBlocking {
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapterRepository = mockk<ChapterRepository>()
        val updateManga = mockk<UpdateManga>()
        val updateChapter = mockk<UpdateChapter>()
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
        val getExcludedScanlators = mockk<GetExcludedScanlators>()
        val libraryPreferences = mockk<LibraryPreferences>()
        val markDuplicatePreference = mockk<Preference<Set<String>>>()

        val staleDownloaded = Chapter.create().copy(
            id = 51,
            mangaId = 10,
            read = true,
            bookmark = true,
            lastPageRead = 19,
            dateFetch = 100,
            chapterNumber = 11.0,
            name = "Chapter 11_ The Point Of No Return_b1f4ca",
            url = "/chapter-11-old",
        )
        val canonicalInDb = Chapter.create().copy(
            id = 52,
            mangaId = 10,
            read = false,
            bookmark = false,
            lastPageRead = 0,
            dateFetch = 200,
            chapterNumber = 11.0,
            name = "Ch. 11 - The Point Of No Return",
            url = "/chapter-11-new",
        )
        val manga = Manga.create().copy(id = 10, source = 0L, ogTitle = "Test Manga", title = "Test Manga")
        val sourceChapter = SChapter.create().apply {
            name = "Ch. 11 - The Point Of No Return"
            url = "/chapter-11-new"
            chapter_number = 11f
        }

        coEvery { getChaptersByMangaId.await(manga.id) } returns listOf(staleDownloaded, canonicalInDb)
        every {
            downloadManager.isChapterDownloaded(
                chapterName = any(),
                chapterScanlator = any(),
                chapterUrl = any(),
                mangaTitle = any(),
                sourceId = any(),
            )
        } answers {
            arg<String>(2) == "/chapter-11-old"
        }
        coEvery { chapterRepository.removeChaptersWithIds(any()) } returns Unit
        coEvery { chapterRepository.addAll(any()) } answers { firstArg() }
        coEvery { updateChapter.awaitAll(any()) } returns Unit
        coEvery { updateManga.awaitUpdateFetchInterval(any(), any(), any()) } returns true
        coEvery { updateManga.awaitUpdateLastUpdate(any()) } returns true
        coEvery { getExcludedScanlators.await(manga.id) } returns emptyList()
        every { libraryPreferences.markDuplicateReadChapterAsRead() } returns markDuplicatePreference
        every { markDuplicatePreference.get() } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            updateChapter = updateChapter,
            getChaptersByMangaId = getChaptersByMangaId,
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )

        sync.await(
            rawSourceChapters = listOf(sourceChapter),
            manga = manga,
            source = localSource(),
            manualFetch = false,
        )

        coVerify(exactly = 1) { chapterRepository.removeChaptersWithIds(match { 51L in it }) }
        coVerify(exactly = 1) {
            updateChapter.awaitAll(
                match { updates ->
                    updates.any {
                        it.id == 52L &&
                            it.read == true &&
                            it.bookmark == true &&
                            it.lastPageRead == 19L
                    }
                },
            )
        }
    }

    @Test
    fun `downloaded stale chapter without canonical replacement should remain retained`() = runBlocking {
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapterRepository = mockk<ChapterRepository>()
        val updateManga = mockk<UpdateManga>()
        val updateChapter = mockk<UpdateChapter>()
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
        val getExcludedScanlators = mockk<GetExcludedScanlators>()
        val libraryPreferences = mockk<LibraryPreferences>()
        val markDuplicatePreference = mockk<Preference<Set<String>>>()

        val staleDownloaded = Chapter.create().copy(
            id = 61,
            mangaId = 10,
            read = false,
            chapterNumber = 40.0,
            name = "Chapter 40",
            url = "/chapter-40-old",
        )
        val manga = Manga.create().copy(id = 10, source = 0L, ogTitle = "Test Manga", title = "Test Manga")

        coEvery { getChaptersByMangaId.await(manga.id) } returns listOf(staleDownloaded)
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any(), any()) } returns true
        coEvery { chapterRepository.removeChaptersWithIds(any()) } returns Unit
        coEvery { chapterRepository.addAll(any()) } answers { firstArg() }
        coEvery { updateChapter.awaitAll(any()) } returns Unit
        coEvery { updateManga.awaitUpdateFetchInterval(any(), any(), any()) } returns true
        coEvery { updateManga.awaitUpdateLastUpdate(any()) } returns true
        coEvery { getExcludedScanlators.await(manga.id) } returns emptyList()
        every { libraryPreferences.markDuplicateReadChapterAsRead() } returns markDuplicatePreference
        every { markDuplicatePreference.get() } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            updateChapter = updateChapter,
            getChaptersByMangaId = getChaptersByMangaId,
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )

        sync.await(
            rawSourceChapters = emptyList(),
            manga = manga,
            source = localSource(),
            manualFetch = false,
        )

        coVerify(exactly = 0) { chapterRepository.removeChaptersWithIds(match { 61L in it }) }
    }

    @Test
    fun `stable no-op sync should still cleanup hash-suffixed downloaded duplicate`() = runBlocking {
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapterRepository = mockk<ChapterRepository>()
        val updateManga = mockk<UpdateManga>()
        val updateChapter = mockk<UpdateChapter>()
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
        val getExcludedScanlators = mockk<GetExcludedScanlators>()
        val libraryPreferences = mockk<LibraryPreferences>()
        val markDuplicatePreference = mockk<Preference<Set<String>>>()

        val staleDownloaded = Chapter.create().copy(
            id = 91,
            mangaId = 10,
            read = true,
            bookmark = true,
            lastPageRead = 22,
            chapterNumber = 5.2,
            name = "Chapter 5.2_ When it rains (Part 2)_02200e",
            url = "/chapter-5-2-old",
        )
        val canonicalStable = Chapter.create().copy(
            id = 92,
            mangaId = 10,
            read = false,
            bookmark = false,
            lastPageRead = 0,
            chapterNumber = 5.2,
            name = "Ch. 5.2 - When it rains (Part 2)",
            url = "/chapter-5-2-new",
        )
        val manga = Manga.create().copy(id = 10, source = 0L, ogTitle = "Test Manga", title = "Test Manga")
        val sourceOld = SChapter.create().apply {
            name = "Chapter 5.2_ When it rains (Part 2)_02200e"
            url = "/chapter-5-2-old"
            chapter_number = 5.2f
        }
        val sourceNew = SChapter.create().apply {
            name = "Ch. 5.2 - When it rains (Part 2)"
            url = "/chapter-5-2-new"
            chapter_number = 5.2f
        }

        coEvery { getChaptersByMangaId.await(manga.id) } returns listOf(staleDownloaded, canonicalStable)
        every {
            downloadManager.isChapterDownloaded(
                chapterName = any(),
                chapterScanlator = any(),
                chapterUrl = any(),
                mangaTitle = any(),
                sourceId = any(),
            )
        } answers {
            arg<String>(2) == "/chapter-5-2-old"
        }
        coEvery { downloadManager.renameChapter(any(), any(), any(), any()) } returns Unit
        coEvery { chapterRepository.removeChaptersWithIds(any()) } returns Unit
        coEvery { chapterRepository.addAll(any()) } answers { firstArg() }
        coEvery { updateChapter.awaitAll(any()) } returns Unit
        coEvery { updateManga.awaitUpdateFetchInterval(any(), any(), any()) } returns true
        coEvery { updateManga.awaitUpdateLastUpdate(any()) } returns true
        coEvery { getExcludedScanlators.await(manga.id) } returns emptyList()
        every { libraryPreferences.markDuplicateReadChapterAsRead() } returns markDuplicatePreference
        every { markDuplicatePreference.get() } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            updateChapter = updateChapter,
            getChaptersByMangaId = getChaptersByMangaId,
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )

        sync.await(
            rawSourceChapters = listOf(sourceOld, sourceNew),
            manga = manga,
            source = localSource(),
            manualFetch = false,
        )

        coVerify(exactly = 1) { chapterRepository.removeChaptersWithIds(match { 91L in it }) }
        coVerify(exactly = 1) {
            updateChapter.awaitAll(
                match { updates ->
                    updates.any {
                        it.id == 92L &&
                            it.read == true &&
                            it.bookmark == true &&
                            it.lastPageRead == 22L
                    }
                },
            )
        }
    }

    @Test
    fun `stable no-op sync should cleanup screenshot style 5_1 duplicate pair`() = runBlocking {
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapterRepository = mockk<ChapterRepository>()
        val updateManga = mockk<UpdateManga>()
        val updateChapter = mockk<UpdateChapter>()
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
        val getExcludedScanlators = mockk<GetExcludedScanlators>()
        val libraryPreferences = mockk<LibraryPreferences>()
        val markDuplicatePreference = mockk<Preference<Set<String>>>()

        val staleDownloaded = Chapter.create().copy(
            id = 101,
            mangaId = 10,
            read = true,
            chapterNumber = 5.1,
            name = "Chapter 5.1_ When it Rains (Part 1)_70d265",
            url = "orphaned://Chapter 5.1_ When it Rains (Part 1)_70d265",
        )
        val canonicalStable = Chapter.create().copy(
            id = 102,
            mangaId = 10,
            read = false,
            chapterNumber = 5.1,
            name = "Ch. 5.1 - When it Rains (Part 1)",
            url = "/chapter-5-1-new",
        )
        val manga = Manga.create().copy(id = 10, source = 0L, ogTitle = "Test Manga", title = "Test Manga")
        val sourceNew = SChapter.create().apply {
            name = "Ch. 5.1 - When it Rains (Part 1)"
            url = "/chapter-5-1-new"
            chapter_number = 5.1f
        }

        coEvery { getChaptersByMangaId.await(manga.id) } returns listOf(staleDownloaded, canonicalStable)
        every {
            downloadManager.isChapterDownloaded(
                chapterName = any(),
                chapterScanlator = any(),
                chapterUrl = any(),
                mangaTitle = any(),
                sourceId = any(),
            )
        } returns false
        coEvery { downloadManager.renameChapter(any(), any(), any(), any()) } returns Unit
        coEvery { chapterRepository.removeChaptersWithIds(any()) } returns Unit
        coEvery { chapterRepository.addAll(any()) } answers { firstArg() }
        coEvery { updateChapter.awaitAll(any()) } returns Unit
        coEvery { updateManga.awaitUpdateFetchInterval(any(), any(), any()) } returns true
        coEvery { updateManga.awaitUpdateLastUpdate(any()) } returns true
        coEvery { getExcludedScanlators.await(manga.id) } returns emptyList()
        every { libraryPreferences.markDuplicateReadChapterAsRead() } returns markDuplicatePreference
        every { markDuplicatePreference.get() } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            updateChapter = updateChapter,
            getChaptersByMangaId = getChaptersByMangaId,
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )

        sync.await(
            rawSourceChapters = listOf(sourceNew),
            manga = manga,
            source = localSource(),
            manualFetch = false,
        )

        coVerify(exactly = 1) { chapterRepository.removeChaptersWithIds(match { 101L in it }) }
    }

    @Test
    fun `float imprecise decimal chapter numbers should reconcile orphaned duplicate with catalog row`() = runBlocking {
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapterRepository = mockk<ChapterRepository>()
        val updateManga = mockk<UpdateManga>()
        val updateChapter = mockk<UpdateChapter>()
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
        val getExcludedScanlators = mockk<GetExcludedScanlators>()
        val libraryPreferences = mockk<LibraryPreferences>()
        val markDuplicatePreference = mockk<Preference<Set<String>>>()

        val staleOrphan51 = Chapter.create().copy(
            id = 111,
            mangaId = 10,
            read = true,
            bookmark = true,
            lastPageRead = 14,
            dateFetch = 100,
            chapterNumber = 5.1,
            name = "Chapter 5.1_ When it Rains (Part 1)_70d265",
            url = "orphaned://Chapter 5.1_ When it Rains (Part 1)_70d265",
        )
        val canonical51 = Chapter.create().copy(
            id = 112,
            mangaId = 10,
            read = false,
            bookmark = false,
            lastPageRead = 0,
            dateFetch = 200,
            chapterNumber = 5.099999904632568,
            name = "Ch. 5.1 - When it Rains (Part 1)",
            url = "/chapter-5-1-md",
        )
        val staleOrphan52 = Chapter.create().copy(
            id = 113,
            mangaId = 10,
            read = true,
            bookmark = false,
            lastPageRead = 9,
            dateFetch = 150,
            chapterNumber = 5.2,
            name = "Chapter 5.2_ When it rains (Part 2)_02200e",
            url = "orphaned://Chapter 5.2_ When it rains (Part 2)_02200e",
        )
        val canonical52 = Chapter.create().copy(
            id = 114,
            mangaId = 10,
            read = false,
            bookmark = false,
            lastPageRead = 0,
            dateFetch = 250,
            chapterNumber = 5.199999809265137,
            name = "Ch. 5.2 - When it rains (Part 2)",
            url = "/chapter-5-2-md",
        )
        val manga = Manga.create().copy(id = 10, source = 1L, ogTitle = "Orcsen Oukokushi", title = "Orcsen Oukokushi")
        val source51 = SChapter.create().apply {
            name = "Ch. 5.1 - When it Rains (Part 1)"
            url = "/chapter-5-1-md"
            chapter_number = 5.1f
        }
        val source52 = SChapter.create().apply {
            name = "Ch. 5.2 - When it rains (Part 2)"
            url = "/chapter-5-2-md"
            chapter_number = 5.2f
        }

        coEvery { getChaptersByMangaId.await(manga.id) } returns listOf(
            staleOrphan51,
            canonical51,
            staleOrphan52,
            canonical52,
        )
        every {
            downloadManager.isChapterDownloaded(
                chapterName = any(),
                chapterScanlator = any(),
                chapterUrl = any(),
                mangaTitle = any(),
                sourceId = any(),
            )
        } returns false
        coEvery { downloadManager.renameChapter(any(), any(), any(), any()) } returns Unit
        coEvery { chapterRepository.removeChaptersWithIds(any()) } returns Unit
        coEvery { chapterRepository.addAll(any()) } answers { firstArg() }
        coEvery { updateChapter.awaitAll(any()) } returns Unit
        coEvery { updateManga.awaitUpdateFetchInterval(any(), any(), any()) } returns true
        coEvery { updateManga.awaitUpdateLastUpdate(any()) } returns true
        coEvery { getExcludedScanlators.await(manga.id) } returns emptyList()
        every { libraryPreferences.markDuplicateReadChapterAsRead() } returns markDuplicatePreference
        every { markDuplicatePreference.get() } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            updateChapter = updateChapter,
            getChaptersByMangaId = getChaptersByMangaId,
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )

        sync.await(
            rawSourceChapters = listOf(source51, source52),
            manga = manga,
            source = remoteSource(),
            manualFetch = false,
        )

        coVerify(exactly = 1) {
            chapterRepository.removeChaptersWithIds(
                match { removedIds ->
                    111L in removedIds && 113L in removedIds && removedIds.size == 2
                },
            )
        }
        coVerify(exactly = 1) {
            updateChapter.awaitAll(
                match { updates ->
                    updates.any {
                        it.id == 112L &&
                            it.read == true &&
                            it.bookmark == true &&
                            it.lastPageRead == 14L
                    } &&
                        updates.any {
                            it.id == 114L &&
                                it.read == true &&
                                it.bookmark == false &&
                                it.lastPageRead == 9L
                        }
                },
            )
        }
        coVerify(exactly = 2) { downloadManager.renameChapter(any(), any(), any(), any()) }
    }

    @Test
    fun `float imprecise decimal chapters should not cross-reconcile between different subchapter numbers`() = runBlocking {
        runImpreciseCrossReconcileGuard(
            staleId = 121L,
            sourceChapters = listOf(
                sChapter(name = "Ch. 5", url = "/chapter-5-0", chapterNumber = 5f),
                sChapter(
                    name = "Ch. 5.2 - When it rains (Part 2)",
                    url = "/chapter-5-2-md",
                    chapterNumber = 5.2f,
                ),
            ),
            dbChapters = listOf(
                orphanChapter(
                    id = 121L,
                    chapterNumber = 5.1,
                    name = "Chapter 5.1_ When it Rains (Part 1)_70d265",
                    url = "orphaned://Chapter 5.1_ When it Rains (Part 1)_70d265",
                ),
                catalogChapter(
                    id = 123L,
                    chapterNumber = 5.0,
                    name = "Ch. 5",
                    url = "/chapter-5-0",
                ),
                catalogChapter(
                    id = 124L,
                    chapterNumber = 5.199999809265137,
                    name = "Ch. 5.2 - When it rains (Part 2)",
                    url = "/chapter-5-2-md",
                ),
            ),
        )

        runImpreciseCrossReconcileGuard(
            staleId = 122L,
            sourceChapters = listOf(
                sChapter(
                    name = "Ch. 5.1 - When it Rains (Part 1)",
                    url = "/chapter-5-1-md",
                    chapterNumber = 5.1f,
                ),
            ),
            dbChapters = listOf(
                orphanChapter(
                    id = 122L,
                    chapterNumber = 5.2,
                    name = "Chapter 5.2_ When it rains (Part 2)_02200e",
                    url = "orphaned://Chapter 5.2_ When it rains (Part 2)_02200e",
                ),
                catalogChapter(
                    id = 125L,
                    chapterNumber = 5.099999904632568,
                    name = "Ch. 5.1 - When it Rains (Part 1)",
                    url = "/chapter-5-1-md",
                ),
            ),
        )
    }

    private suspend fun runImpreciseCrossReconcileGuard(
        staleId: Long,
        sourceChapters: List<SChapter>,
        dbChapters: List<Chapter>,
    ) {
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapterRepository = mockk<ChapterRepository>()
        val updateManga = mockk<UpdateManga>()
        val updateChapter = mockk<UpdateChapter>()
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
        val getExcludedScanlators = mockk<GetExcludedScanlators>()
        val libraryPreferences = mockk<LibraryPreferences>()
        val markDuplicatePreference = mockk<Preference<Set<String>>>()
        val manga = Manga.create().copy(id = 10, source = 0L, ogTitle = "Test Manga", title = "Test Manga")

        coEvery { getChaptersByMangaId.await(manga.id) } returns dbChapters
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any(), any()) } returns false
        coEvery { chapterRepository.removeChaptersWithIds(any()) } returns Unit
        coEvery { chapterRepository.addAll(any()) } answers { firstArg() }
        coEvery { updateChapter.awaitAll(any()) } returns Unit
        coEvery { updateManga.awaitUpdateFetchInterval(any(), any(), any()) } returns true
        coEvery { updateManga.awaitUpdateLastUpdate(any()) } returns true
        coEvery { getExcludedScanlators.await(manga.id) } returns emptyList()
        every { libraryPreferences.markDuplicateReadChapterAsRead() } returns markDuplicatePreference
        every { markDuplicatePreference.get() } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            updateChapter = updateChapter,
            getChaptersByMangaId = getChaptersByMangaId,
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )

        sync.await(
            rawSourceChapters = sourceChapters,
            manga = manga,
            source = localSource(),
            manualFetch = false,
        )

        coVerify(exactly = 0) {
            chapterRepository.removeChaptersWithIds(match { staleId in it })
        }
    }

    private fun orphanChapter(
        id: Long,
        chapterNumber: Double,
        name: String,
        url: String,
    ): Chapter {
        return Chapter.create().copy(
            id = id,
            mangaId = 10,
            chapterNumber = chapterNumber,
            name = name,
            url = url,
        )
    }

    private fun catalogChapter(
        id: Long,
        chapterNumber: Double,
        name: String,
        url: String,
    ): Chapter {
        return Chapter.create().copy(
            id = id,
            mangaId = 10,
            chapterNumber = chapterNumber,
            name = name,
            url = url,
        )
    }

    private fun sChapter(
        name: String,
        url: String,
        chapterNumber: Float,
    ): SChapter {
        return SChapter.create().apply {
            this.name = name
            this.url = url
            this.chapter_number = chapterNumber
        }
    }

    private fun chapter(
        id: Long,
        read: Boolean,
        lastPageRead: Long,
        url: String,
    ): Chapter {
        return Chapter.create().copy(
            id = id,
            mangaId = 10,
            read = read,
            lastPageRead = lastPageRead,
            chapterNumber = id.toDouble(),
            name = "Chapter $id",
            url = url,
        )
    }

    private fun localSource(): Source = testSource(id = 0L, name = "Local")

    private fun remoteSource(): Source = testSource(id = 1L, name = "Remote")

    private fun testSource(id: Long, name: String): Source {
        return object : Source {
            override val id: Long = id
            override val name: String = name
            override val supportsLatest: Boolean = false

            override suspend fun getPopularManga(page: Int): MangasPage =
                throw UnsupportedOperationException()

            override suspend fun getLatestUpdates(page: Int): MangasPage =
                throw UnsupportedOperationException()

            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
                throw UnsupportedOperationException()

            override suspend fun getMangaUpdate(
                manga: SManga,
                chapters: List<SChapter>,
                fetchDetails: Boolean,
                fetchChapters: Boolean,
            ): SMangaUpdate = SMangaUpdate(manga, chapters)

            override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()

            override suspend fun getRelatedMangaList(
                manga: SManga,
                exceptionHandler: (Throwable) -> Unit,
                pushResults: suspend (relatedManga: Pair<String, List<SManga>>, completed: Boolean) -> Unit,
            ) {
                assertTrue(true)
            }
        }
    }
}
