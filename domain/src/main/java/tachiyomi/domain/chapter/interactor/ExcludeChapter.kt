package tachiyomi.domain.chapter.interactor

// KMK -->
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository

class ExcludeChapter(
    private val chapterRepository: ChapterRepository,
) {
    suspend fun await(chapters: List<Chapter>, excluded: Boolean) {
        val updates = chapters.map { chapter ->
            ChapterUpdate(
                id = chapter.id,
                excluded = excluded,
            )
        }
        chapterRepository.updateAll(updates)
    }
}
// KMK <--
