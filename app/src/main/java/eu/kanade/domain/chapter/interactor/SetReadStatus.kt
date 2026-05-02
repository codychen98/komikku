package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.download.interactor.DeleteDownload
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.ui.library.LibraryScreenModel
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.util.chapter.unreadSkippedSubChapterDuplicates
import eu.kanade.tachiyomi.ui.updates.UpdatesScreenModel
import exh.source.MERGED_SOURCE_ID
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.math.floor

class SetReadStatus(
    private val downloadPreferences: DownloadPreferences,
    private val deleteDownload: DeleteDownload,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    // SY -->
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId,
    // SY <--
) {

    private val mapper = { chapter: Chapter, read: Boolean ->
        ChapterUpdate(
            read = read,
            lastPageRead = if (!read) 0 else null,
            id = chapter.id,
        )
    }

    /**
     * Mark chapters as read/unread, also delete downloaded chapters if 'After manually marked as read' is set.
     *
     * Called from:
     *  - [LibraryScreenModel]: Manually select mangas & mark as read
     *  - [MangaScreenModel.markChaptersRead]: Manually select chapters & mark as read or swipe chapter as read
     *  - [UpdatesScreenModel.markUpdatesRead]: Manually select chapters & mark as read
     *  - [LibraryUpdateJob.updateChapterList]: when a manga is updated and has new chapter but already read,
     *  it will mark that new **duplicated** chapter as read & delete downloading/downloaded -> should be treat as
     *  automatically ~ no auto delete
     *  - [ReaderViewModel.updateChapterProgress]: mark **duplicated** chapter as read after finish reading -> should be
     *  treated as not manually mark as read so not auto-delete (there are cases where chapter number is mistaken by volume number)
     */
    suspend fun await(
        read: Boolean,
        vararg chapters: Chapter,
        // KMK -->
        manually: Boolean = true,
        // KMK <--
    ): Result = withNonCancellableContext {
        val chaptersToUpdate = chapters.filter {
            when (read) {
                true -> !it.read
                false -> it.read || it.lastPageRead > 0
            }
        }
        if (chaptersToUpdate.isEmpty()) {
            return@withNonCancellableContext Result.NoChapters
        }

        try {
            chapterRepository.updateAll(
                chaptersToUpdate.map { mapper(it, read) },
            )
            if (read) {
                markSkippedSubChapterDuplicatesAsRead(chaptersToUpdate)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }

        if (
            // KMK -->
            manually &&
            // KMK <--
            read &&
            downloadPreferences.removeAfterMarkedAsRead().get()
        ) {
            chaptersToUpdate
                // KMK -->
                .map { it.copy(read = true) } // mark as read so it will respect category exclusion
                // KMK <--
                .groupBy { it.mangaId }
                .forEach { (mangaId, chapters) ->
                    deleteDownload.awaitAll(
                        manga = mangaRepository.getMangaById(mangaId),
                        chapters = chapters.toTypedArray(),
                    )
                }
        }

        Result.Success
    }

    suspend fun await(
        manga: Manga,
        read: Boolean,
        vararg chapters: Chapter,
        manually: Boolean = true,
    ): Result = withNonCancellableContext {
        val newlyReadWholeNumbers = if (read) {
            chapters
                .filter { !it.read && it.chapterNumber >= 0 && it.chapterNumber == floor(it.chapterNumber) }
                .map { floor(it.chapterNumber) }
                .toSet()
        } else {
            emptySet()
        }

        val result = await(
            read = read,
            chapters = chapters,
            manually = manually,
        )
        if (
            result != Result.Success ||
            !read ||
            manga.source != MERGED_SOURCE_ID ||
            !manga.skipSubChapterDuplicates ||
            newlyReadWholeNumbers.isEmpty()
        ) {
            return@withNonCancellableContext result
        }

        val mergedChapterUpdates = getMergedChaptersByMangaId
            .await(
                mangaId = manga.id,
                dedupe = false,
                applyFilter = false,
            )
            .unreadSkippedSubChapterDuplicates()
            .filter { chapter -> floor(chapter.chapterNumber) in newlyReadWholeNumbers }
            .map { chapter -> ChapterUpdate(id = chapter.id, read = true) }
        if (mergedChapterUpdates.isNotEmpty()) {
            chapterRepository.updateAll(mergedChapterUpdates)
        }

        result
    }

    private suspend fun markSkippedSubChapterDuplicatesAsRead(
        chaptersMarkedRead: List<Chapter>,
    ) {
        val newlyReadWholeNumbersByManga = chaptersMarkedRead
            .asSequence()
            .map { chapter -> chapter.mangaId to chapter.chapterNumber }
            .filter { (_, chapterNumber) ->
                chapterNumber >= 0 && chapterNumber == floor(chapterNumber)
            }
            .groupBy(
                keySelector = { (mangaId, _) -> mangaId },
                valueTransform = { (_, chapterNumber) -> floor(chapterNumber) },
            )
            .mapValues { (_, chapterNumbers) -> chapterNumbers.toSet() }
        if (newlyReadWholeNumbersByManga.isEmpty()) return

        val chapterUpdates = newlyReadWholeNumbersByManga.flatMap { (mangaId, newlyReadWholeNumbers) ->
            val manga = mangaRepository.getMangaById(mangaId)
            if (!manga.skipSubChapterDuplicates) return@flatMap emptyList()

            chapterRepository
                .getChapterByMangaId(mangaId, applyFilter = false)
                .unreadSkippedSubChapterDuplicates()
                .filter { chapter -> floor(chapter.chapterNumber) in newlyReadWholeNumbers }
                .map { chapter -> ChapterUpdate(id = chapter.id, read = true) }
        }
        if (chapterUpdates.isEmpty()) return
        chapterRepository.updateAll(chapterUpdates)
    }

    suspend fun await(mangaId: Long, read: Boolean): Result = withNonCancellableContext {
        await(
            read = read,
            chapters = chapterRepository
                .getChapterByMangaId(mangaId)
                .toTypedArray(),
        )
    }

    // SY -->
    private suspend fun awaitMerged(mangaId: Long, read: Boolean) = withNonCancellableContext f@{
        val manga = mangaRepository.getMangaById(mangaId)
        return@f await(
            manga = manga,
            read = read,
            chapters = getMergedChaptersByMangaId
                .await(mangaId, dedupe = false)
                .toTypedArray(),
        )
    }

    suspend fun await(manga: Manga, read: Boolean) = if (manga.source == MERGED_SOURCE_ID) {
        awaitMerged(manga.id, read)
    } else {
        await(manga.id, read)
    }
    // SY <--

    sealed interface Result {
        data object Success : Result
        data object NoChapters : Result
        data class InternalError(val error: Throwable) : Result
    }
}
