package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
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

    private fun localSource(): Source {
        return object : Source {
            override val id: Long = 0L
            override val name: String = "Local"
            override suspend fun getMangaDetails(manga: SManga): SManga = manga
            override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
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
