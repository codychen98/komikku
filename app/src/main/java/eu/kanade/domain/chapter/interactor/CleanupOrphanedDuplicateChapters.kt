package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.chapter.interactor.OrphanChapterComicInfoLink.ComicInfoWebChapterMatch
import eu.kanade.tachiyomi.data.download.DownloadProvider
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetAllManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal

/**
 * Removes false `orphaned://` chapter rows when on-disk `ComicInfo.xml` `<Web>` uniquely matches
 * a catalog chapter for the same manga. Invoked from [eu.kanade.tachiyomi.ui.download.reindexDownloads]
 * (full library) and after reindex merge (parent manga only) following [RestoreOrphanedChapters].
 * See `roadmap/duplicated chapters/orphan_chapter_duplicates_implementation.md`.
 */
class CleanupOrphanedDuplicateChapters(
    private val getAllManga: GetAllManga,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val chapterRepository: ChapterRepository,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val comicInfoReader: ChapterDownloadComicInfoReader,
) {
    /**
     * @param mangaIds When null, scans every manga in the library. When non-null, only those IDs
     *   are processed (after reindex merge, only the parent id is passed).
     */
    suspend fun await(mangaIds: Collection<Long>? = null): Int {
        return resolveMangaList(mangaIds).sumOf { cleanupForManga(it) }
    }

    private suspend fun resolveMangaList(mangaIds: Collection<Long>?): List<Manga> {
        if (mangaIds == null) {
            return getAllManga.await()
        }
        return mangaIds.distinct().mapNotNull { getManga.await(it) }
    }

    private suspend fun cleanupForManga(manga: Manga): Int {
        val source = sourceManager.getOrStub(manga.source)
        if (source.isLocal()) return 0

        val mangaDir = downloadProvider.findMangaDir(manga.ogTitle, source) ?: return 0
        val dbChapters = getChaptersByMangaId.await(manga.id)
        val orphans = dbChapters.filter { it.url.startsWith("orphaned://", ignoreCase = true) }
        if (orphans.isEmpty()) return 0

        var removed = 0
        for (orphan in orphans) {
            val entry = mangaDir.findFile(orphan.name)
                ?: mangaDir.findFile(orphan.name + ".cbz")
                ?: continue

            val comicInfo = comicInfoReader.readComicInfo(entry) ?: continue
            val match = OrphanChapterComicInfoLink.matchCatalogChapterFromComicInfo(comicInfo, dbChapters)
            val catalog = (match as? ComicInfoWebChapterMatch.Unique)?.chapter ?: continue

            chapterRepository.update(
                OrphanChapterComicInfoLink.mergeOrphanProgressOntoCatalogChapter(catalog, orphan),
            )
            chapterRepository.removeChaptersWithIds(listOf(orphan.id))
            removed += 1
        }
        return removed
    }
}
