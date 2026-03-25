package tachiyomi.domain.chapter.interactor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository

class ReorderChapter(
    private val chapterRepository: ChapterRepository,
) {
    private val mutex = Mutex()

    suspend fun await(mangaId: Long, chapter: Chapter, newIndex: Int) = withNonCancellableContext {
        mutex.withLock {
            val chapters = chapterRepository.getChapterByMangaId(mangaId)
                .sortedWith(
                    Comparator { c1, c2 ->
                        val o1 = c1.customSortOrder ?: Long.MAX_VALUE
                        val o2 = c2.customSortOrder ?: Long.MAX_VALUE
                        if (o1 != o2) o1.compareTo(o2) else c1.sourceOrder.compareTo(c2.sourceOrder)
                    },
                )
                .toMutableList()

            val currentIndex = chapters.indexOfFirst { it.id == chapter.id }
            if (currentIndex == -1) {
                return@withNonCancellableContext Result.Unchanged
            }

            val clampedIndex = newIndex.coerceIn(0, chapters.size - 1)
            if (currentIndex == clampedIndex) {
                return@withNonCancellableContext Result.Unchanged
            }

            try {
                chapters.add(clampedIndex, chapters.removeAt(currentIndex))

                val updates = chapters.mapIndexed { index, c ->
                    ChapterUpdate(
                        id = c.id,
                        customSortOrder = index.toLong(),
                    )
                }

                chapterRepository.updateAll(updates)
                Result.Success
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                Result.InternalError(e)
            }
        }
    }

    sealed interface Result {
        data object Success : Result
        data object Unchanged : Result
        data class InternalError(val error: Throwable) : Result
    }
}
