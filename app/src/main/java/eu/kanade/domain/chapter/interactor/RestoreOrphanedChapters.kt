package eu.kanade.domain.chapter.interactor

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.Downloader
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.interactor.GetAllManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal

/**
 * Scans each manga download directory for chapter folders (or `.cbz`) whose names are absent from
 * [eu.kanade.tachiyomi.data.download.DownloadProvider.getValidChapterDirNames] for chapters already
 * in the database. Each such path becomes a new chapter row with `url` prefixed by `orphaned://`.
 *
 * This interactor is invoked after cache invalidation by [eu.kanade.tachiyomi.ui.download.reindexDownloads].
 * When [eu.kanade.tachiyomi.data.download.DownloadProvider.getChapterDirName] includes a hash of the
 * chapter URL and the DB `url` later drifts, a legitimate download folder can be misclassified as
 * orphan. See `roadmap/duplicated chapters/orphan_chapter_duplicates_implementation.md`.
 *
 * Before inserting an orphan, `ComicInfo.xml` is read when present (see [tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE]); if
 * [OrphanChapterComicInfoLink] finds exactly one matching catalog chapter via `<Web>`, the folder
 * is skipped. Ambiguous `<Web>` matches skip orphan insertion to avoid wrong merges.
 */
class RestoreOrphanedChapters(
    private val getAllManga: GetAllManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val chapterRepository: ChapterRepository,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val comicInfoReader: ChapterDownloadComicInfoReader,
) {
    suspend fun await(): Int {
        var restored = 0
        val allManga = getAllManga.await()

        for (manga in allManga) {
            val source = sourceManager.getOrStub(manga.source)
            if (source.isLocal()) continue

            val mangaDir = downloadProvider.findMangaDir(manga.ogTitle, source) ?: continue
            val dbChapters = getChaptersByMangaId.await(manga.id)

            val knownFolderNames = dbChapters.flatMap { chapter ->
                downloadProvider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
            }.toHashSet()

            val knownChapterNumbers = dbChapters
                .filter { !it.url.startsWith("orphaned://") && it.isRecognizedNumber }
                .map { it.chapterNumber }
                .toHashSet()

            val candidateFiles = mangaDir.listFiles().orEmpty()
                .filter { file ->
                    val fileName = file.name ?: return@filter false
                    (file.isDirectory || fileName.endsWith(".cbz")) &&
                        fileName !in knownFolderNames &&
                        !fileName.endsWith(Downloader.TMP_DIR_SUFFIX)
                }

            val orphanedChapters = mutableListOf<Chapter>()
            for (dir in candidateFiles) {
                val fileName = dir.name ?: continue
                val chapterName = if (fileName.endsWith(".cbz")) {
                    fileName.dropLast(4)
                } else {
                    if (!dir.isDirectory) continue
                    val resolved = downloadProvider.resolveChapterImageDir(dir)
                    if (!resolved.isValid) continue
                    resolved.chapterName
                }

                val chapterNumber = ChapterRecognition.parseChapterNumber(
                    manga.title,
                    chapterName,
                    -1.0,
                )
                if (chapterNumber >= 0 && chapterNumber in knownChapterNumbers) continue

                val comicInfo = readComicInfoFromDownloadEntry(dir)
                when (OrphanChapterComicInfoLink.matchCatalogChapterFromComicInfo(comicInfo, dbChapters)) {
                    is OrphanChapterComicInfoLink.ComicInfoWebChapterMatch.Unique,
                    is OrphanChapterComicInfoLink.ComicInfoWebChapterMatch.Ambiguous,
                    -> continue
                    is OrphanChapterComicInfoLink.ComicInfoWebChapterMatch.None -> Unit
                }

                orphanedChapters.add(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "orphaned://$chapterName",
                        name = chapterName,
                        chapterNumber = chapterNumber,
                        sourceOrder = -1L,
                        dateFetch = dir.lastModified(),
                        dateUpload = 0L,
                    ),
                )
            }

            if (orphanedChapters.isNotEmpty()) {
                chapterRepository.addAll(orphanedChapters)
                restored += orphanedChapters.size
            }
        }

        return restored
    }

    private suspend fun readComicInfoFromDownloadEntry(file: UniFile): ComicInfo? =
        comicInfoReader.readComicInfo(file)
}
