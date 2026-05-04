package eu.kanade.domain.chapter.interactor

import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.Downloader
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.interactor.GetAllManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal

class RestoreOrphanedChapters(
    private val getAllManga: GetAllManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val chapterRepository: ChapterRepository,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
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

            // Build set of known chapter numbers (non-orphaned) to avoid creating duplicates
            val knownChapterNumbers = dbChapters
                .filter { !it.url.startsWith("orphaned://") && it.isRecognizedNumber }
                .map { it.chapterNumber }
                .toHashSet()

            val orphanedChapters = mangaDir.listFiles().orEmpty()
                .filter { file ->
                    val fileName = file.name ?: return@filter false
                    (file.isDirectory || fileName.endsWith(".cbz")) &&
                        fileName !in knownFolderNames &&
                        !fileName.endsWith(Downloader.TMP_DIR_SUFFIX)
                }
                .mapNotNull { dir ->
                    val fileName = dir.name ?: ""
                    val chapterName = if (fileName.endsWith(".cbz")) {
                        fileName.dropLast(4)
                    } else {
                        if (!dir.isDirectory) return@mapNotNull null
                        val resolved = downloadProvider.resolveChapterImageDir(dir)
                        if (!resolved.isValid) return@mapNotNull null
                        resolved.chapterName
                    }
                    val chapterNumber = ChapterRecognition.parseChapterNumber(
                        manga.title,
                        chapterName,
                        -1.0,
                    )
                    // Skip if a real (non-orphaned) chapter with the same number already exists
                    if (chapterNumber >= 0 && chapterNumber in knownChapterNumbers) return@mapNotNull null
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "orphaned://$chapterName",
                        name = chapterName,
                        chapterNumber = chapterNumber,
                        sourceOrder = -1L,
                        dateFetch = dir.lastModified(),
                        dateUpload = 0L,
                    )
                }

            if (orphanedChapters.isNotEmpty()) {
                chapterRepository.addAll(orphanedChapters)
                restored += orphanedChapters.size
            }
        }

        return restored
    }
}
