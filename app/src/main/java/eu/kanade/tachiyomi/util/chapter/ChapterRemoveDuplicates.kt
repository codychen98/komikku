package eu.kanade.tachiyomi.util.chapter

// KMK -->
import kotlin.math.floor
// KMK <--
import tachiyomi.domain.chapter.model.Chapter

/**
 * Returns a copy of the list with duplicate chapters removed
 */
fun List<Chapter>.removeDuplicates(currentChapter: Chapter): List<Chapter> {
    val deduped = groupBy { it.chapterNumber }
        .map { (_, chapters) ->
            chapters.find { it.id == currentChapter.id }
                ?: chapters.find { it.scanlator == currentChapter.scanlator }
                ?: chapters.first()
        }

    // KMK -->
    val wholeNumbers = deduped
        .map { it.chapterNumber }
        .filter { it >= 0 && it == floor(it) }
        .toSet()

    return deduped.filter { chapter ->
        if (chapter.id == currentChapter.id) return@filter true
        val num = chapter.chapterNumber
        if (num < 0 || num == floor(num)) return@filter true
        floor(num) !in wholeNumbers
    }
    // KMK <--
}
