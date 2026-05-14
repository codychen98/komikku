package eu.kanade.domain.chapter.interactor

import tachiyomi.core.common.util.system.UrlUtils
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate

/**
 * Links on-disk chapter downloads to catalog [Chapter] rows using ComicInfo `<Web>` and
 * [UrlUtils.chapterContentUrlMatchKey]. Used by [RestoreOrphanedChapters] before inserting
 * synthetic `orphaned://` chapters, and by [CleanupOrphanedDuplicateChapters] before deleting them.
 */
object OrphanChapterComicInfoLink {

    private val webTokenSeparators = Regex("[\\s,;|]+")

    fun splitComicInfoWebTokens(webValue: String): List<String> =
        webValue.trim()
            .split(webTokenSeparators)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun normalizedWebUrlKeys(webValue: String): Set<String> =
        splitComicInfoWebTokens(webValue)
            .mapNotNull { UrlUtils.chapterContentUrlMatchKey(it) }
            .toSet()

    sealed interface ComicInfoWebChapterMatch {
        data class Unique(val chapter: Chapter) : ComicInfoWebChapterMatch

        data object Ambiguous : ComicInfoWebChapterMatch

        data object None : ComicInfoWebChapterMatch
    }

    /**
     * Compares ComicInfo `<Web>` tokens (after normalization) to non-orphan [dbChapters] URLs.
     *
     * When [ComicInfoWebChapterMatch.Ambiguous], callers should **not** insert a new orphan row
     * (avoids wrong merges when `<Web>` lists several URLs). [ComicInfoWebChapterMatch.None] means
     * no usable `<Web>` data or no single catalog hit — caller may fall back to legacy orphan
     * insertion rules.
     */
    fun matchCatalogChapterFromComicInfo(
        comicInfo: ComicInfo?,
        dbChapters: List<Chapter>,
    ): ComicInfoWebChapterMatch {
        val webVal = comicInfo?.web?.value?.trim().orEmpty()
        if (webVal.isEmpty()) return ComicInfoWebChapterMatch.None

        val webKeys = normalizedWebUrlKeys(webVal)
        if (webKeys.isEmpty()) return ComicInfoWebChapterMatch.None

        val catalogChapters = dbChapters.filter { !it.url.startsWith("orphaned://", ignoreCase = true) }
        val matched = catalogChapters.filter { chapter ->
            val key = UrlUtils.chapterContentUrlMatchKey(chapter.url) ?: return@filter false
            key in webKeys
        }.distinctBy { it.id }

        return when {
            matched.isEmpty() -> ComicInfoWebChapterMatch.None
            matched.size == 1 -> ComicInfoWebChapterMatch.Unique(matched.first())
            else -> ComicInfoWebChapterMatch.Ambiguous
        }
    }

    /**
     * Merges read progress from a synthetic orphan row onto the catalog chapter before the orphan is deleted.
     */
    fun mergeOrphanProgressOntoCatalogChapter(catalog: Chapter, orphan: Chapter): ChapterUpdate =
        ChapterUpdate(
            id = catalog.id,
            read = catalog.read || orphan.read,
            bookmark = catalog.bookmark || orphan.bookmark,
            lastPageRead = maxOf(catalog.lastPageRead, orphan.lastPageRead),
        )
}
