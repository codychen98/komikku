package tachiyomi.domain.chapter.service

import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

fun getChapterSort(
    manga: Manga,
    sortDescending: Boolean = manga.sortDescending(),
): (
    Chapter,
    Chapter,
) -> Int {
    return when (manga.sorting) {
        Manga.CHAPTER_SORTING_SOURCE -> when (sortDescending) {
            true -> { c1, c2 -> c1.sourceOrder.compareTo(c2.sourceOrder) }
            false -> { c1, c2 -> c2.sourceOrder.compareTo(c1.sourceOrder) }
        }
        Manga.CHAPTER_SORTING_NUMBER -> when (sortDescending) {
            true -> { c1, c2 -> c2.chapterNumber.compareTo(c1.chapterNumber) }
            false -> { c1, c2 -> c1.chapterNumber.compareTo(c2.chapterNumber) }
        }
        Manga.CHAPTER_SORTING_UPLOAD_DATE -> when (sortDescending) {
            true -> { c1, c2 -> c2.dateUpload.compareTo(c1.dateUpload) }
            false -> { c1, c2 -> c1.dateUpload.compareTo(c2.dateUpload) }
        }
        Manga.CHAPTER_SORTING_ALPHABET -> when (sortDescending) {
            true -> { c1, c2 -> c2.name.compareToWithCollator(c1.name) }
            false -> { c1, c2 -> c1.name.compareToWithCollator(c2.name) }
        }
        // KMK -->
        Manga.CHAPTER_SORTING_CUSTOM -> when (sortDescending) {
            true -> { c1, c2 ->
                val o1 = c1.customSortOrder ?: Long.MAX_VALUE
                val o2 = c2.customSortOrder ?: Long.MAX_VALUE
                if (o1 != o2) o1.compareTo(o2) else c1.sourceOrder.compareTo(c2.sourceOrder)
            }
            false -> { c1, c2 ->
                val o1 = c1.customSortOrder ?: Long.MAX_VALUE
                val o2 = c2.customSortOrder ?: Long.MAX_VALUE
                if (o1 != o2) o2.compareTo(o1) else c2.sourceOrder.compareTo(c1.sourceOrder)
            }
        }
        // KMK <--
        else -> throw NotImplementedError("Invalid chapter sorting method: ${manga.sorting}")
    }
}
