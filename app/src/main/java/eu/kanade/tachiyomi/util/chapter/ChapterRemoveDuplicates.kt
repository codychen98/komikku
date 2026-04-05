package eu.kanade.tachiyomi.util.chapter

// KMK -->
import kotlin.math.floor
// KMK <--
import tachiyomi.domain.chapter.model.Chapter

/**
 * Returns a copy of the list with duplicate chapters removed
 */
fun List<Chapter>.removeDuplicates(currentChapter: Chapter): List<Chapter> {
    return groupBy { it.chapterNumber }
        .map { (_, chapters) ->
            chapters.find { it.id == currentChapter.id }
                ?: chapters.find { it.scanlator == currentChapter.scanlator }
                ?: chapters.first()
        }
}

// KMK -->
// Same-number dedup (download context - no current chapter, keeps first by list order)
fun List<Chapter>.removeDuplicates(): List<Chapter> {
    return groupBy { it.chapterNumber }
        .map { (_, chapters) -> chapters.first() }
}

// Sub-chapter dedup (reader context - always keeps currentChapter)
fun List<Chapter>.removeSubChapterDuplicates(currentChapter: Chapter): List<Chapter> {
    val wholeNumbers = map { it.chapterNumber }
        .filter { it >= 0 && it == floor(it) }
        .toSet()

    return filter { chapter ->
        if (chapter.id == currentChapter.id) return@filter true
        val num = chapter.chapterNumber
        if (num < 0 || num == floor(num)) return@filter true
        floor(num) !in wholeNumbers
    }
}

// Sub-chapter dedup (download context - no current chapter to preserve)
fun List<Chapter>.removeSubChapterDuplicates(): List<Chapter> {
    val wholeNumbers = map { it.chapterNumber }
        .filter { it >= 0 && it == floor(it) }
        .toSet()

    return filter { chapter ->
        val num = chapter.chapterNumber
        if (num < 0 || num == floor(num)) return@filter true
        floor(num) !in wholeNumbers
    }
}
// KMK <--
