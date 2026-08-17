package eu.kanade.domain.chapter.interactor

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.download.model.ChapterDownload
import tachiyomi.domain.download.repository.ChapterDownloadRepository
import tachiyomi.domain.manga.interactor.GetAllManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal

/**
 * Scans on-disk chapter downloads and writes [ChapterDownload] registry rows for folders that
 * uniquely match catalog chapters via [DownloadFolderMatcher].
 *
 * Invoked before [RestoreOrphanedChapters] during reindex so URL-drifted folders are linked
 * without inserting `orphaned://` rows.
 *
 * @see `roadmap/downloaded manga not showing up/download_registry_implementation.md`
 */
class ReconcileChapterDownloads(
    private val getAllManga: GetAllManga,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val chapterDownloadRepository: ChapterDownloadRepository,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val comicInfoReader: ChapterDownloadComicInfoReader,
) {

    /**
     * @param mangaIds When null, scans every manga in the library. When non-null, only those IDs
     *   are processed (same contract as [RestoreOrphanedChapters]).
     * @return Count of registry rows created for chapters not previously linked.
     */
    suspend fun await(mangaIds: Collection<Long>? = null): Int {
        return resolveMangaList(mangaIds).sumOf { reconcileForManga(it) }
    }

    private suspend fun resolveMangaList(mangaIds: Collection<Long>?): List<Manga> {
        if (mangaIds == null) {
            return getAllManga.await()
        }
        return mangaIds.distinct().mapNotNull { getManga.await(it) }
    }

    private suspend fun reconcileForManga(manga: Manga): Int {
        val source = sourceManager.getOrStub(manga.source)
        if (source.isLocal()) return 0

        val mangaDir = downloadProvider.findMangaDir(manga.ogTitle, source) ?: return 0
        val dbChapters = getChaptersByMangaId.await(manga.id)
        val existingDownloads = chapterDownloadRepository.getByMangaId(manga.id)
        val linkedPaths = existingDownloads.map { it.relativePath }.toMutableSet()
        val linkedChapterIds = existingDownloads.map { it.chapterId }.toMutableSet()

        var newlyLinked = 0
        for (entry in mangaDir.listFiles().orEmpty()) {
            val fileName = entry.name ?: continue
            if (!isDownloadEntry(entry, fileName)) continue
            if (fileName.endsWith(Downloader.TMP_DIR_SUFFIX)) continue

            val relativePath = buildRelativePath(manga, source, fileName)
            if (relativePath in linkedPaths) continue

            if (!isValidDownloadEntry(entry, fileName)) continue

            val matchedChapter = DownloadFolderMatcher.matchCatalogChapter(
                catalogChapters = dbChapters,
                folderName = fileName,
                mangaTitle = manga.title,
                comicInfo = comicInfoReader.readComicInfo(entry),
            ) ?: continue

            if (matchedChapter.id in linkedChapterIds) {
                updatePathIfChanged(matchedChapter.id, relativePath, existingDownloads)
                continue
            }

            chapterDownloadRepository.upsert(
                ChapterDownload(
                    chapterId = matchedChapter.id,
                    relativePath = relativePath,
                    linkedAt = System.currentTimeMillis(),
                ),
            )
            linkedPaths += relativePath
            linkedChapterIds += matchedChapter.id
            newlyLinked++
        }

        return newlyLinked
    }

    private fun isDownloadEntry(entry: UniFile, fileName: String): Boolean {
        return entry.isDirectory || fileName.endsWith(".cbz")
    }

    private fun isValidDownloadEntry(entry: UniFile, fileName: String): Boolean {
        return downloadProvider.isValidDownloadEntry(entry)
    }

    private suspend fun updatePathIfChanged(
        chapterId: Long,
        relativePath: String,
        existingDownloads: List<ChapterDownload>,
    ) {
        val existing = existingDownloads.firstOrNull { it.chapterId == chapterId } ?: return
        if (existing.relativePath == relativePath) return

        chapterDownloadRepository.upsert(
            existing.copy(
                relativePath = relativePath,
                linkedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun buildRelativePath(manga: Manga, source: Source, entryName: String): String {
        return downloadProvider.getRelativeChapterPath(source, manga.ogTitle, entryName)
    }
}
