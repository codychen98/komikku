package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.chapter.interactor.OrphanChapterComicInfoLink.ComicInfoWebChapterMatch
import eu.kanade.tachiyomi.data.download.DownloadProvider
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetAllManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal

/**
 * Removes false `orphaned://` chapter rows when on-disk `ComicInfo.xml` `<Web>` uniquely matches
 * a catalog chapter for the same manga. Invoked from [eu.kanade.tachiyomi.ui.download.reindexDownloads]
 * after [RestoreOrphanedChapters]. See `roadmap/duplicated chapters/orphan_chapter_duplicates_implementation.md`.
 */
class CleanupOrphanedDuplicateChapters(
    private val getAllManga: GetAllManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val chapterRepository: ChapterRepository,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val comicInfoReader: ChapterDownloadComicInfoReader,
) {
    suspend fun await(): Int {
        var removed = 0
        for (manga in getAllManga.await()) {
            val source = sourceManager.getOrStub(manga.source)
            if (source.isLocal()) continue

            val mangaDir = downloadProvider.findMangaDir(manga.ogTitle, source) ?: continue
            val dbChapters = getChaptersByMangaId.await(manga.id)
            val orphans = dbChapters.filter { it.url.startsWith("orphaned://", ignoreCase = true) }
            if (orphans.isEmpty()) continue

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
        }
        return removed
    }
}
