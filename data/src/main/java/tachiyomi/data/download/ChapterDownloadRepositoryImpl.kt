package tachiyomi.data.download

import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.download.model.ChapterDownload
import tachiyomi.domain.download.repository.ChapterDownloadRepository

class ChapterDownloadRepositoryImpl(
    private val handler: DatabaseHandler,
) : ChapterDownloadRepository {

    override suspend fun insert(chapterDownload: ChapterDownload) {
        handler.await {
            chapter_downloadsQueries.insert(
                chapterId = chapterDownload.chapterId,
                relativePath = chapterDownload.relativePath,
                linkedAt = chapterDownload.linkedAt,
            )
        }
    }

    override suspend fun upsert(chapterDownload: ChapterDownload) {
        handler.await {
            chapter_downloadsQueries.upsert(
                chapterId = chapterDownload.chapterId,
                relativePath = chapterDownload.relativePath,
                linkedAt = chapterDownload.linkedAt,
            )
        }
    }

    override suspend fun deleteByChapterId(chapterId: Long) {
        handler.await {
            chapter_downloadsQueries.deleteByChapterId(chapterId = chapterId)
        }
    }

    override suspend fun getByChapterId(chapterId: Long): ChapterDownload? {
        return handler.awaitOneOrNull {
            chapter_downloadsQueries.getByChapterId(chapterId, ChapterDownloadMapper::mapChapterDownload)
        }
    }

    override suspend fun getByMangaId(mangaId: Long): List<ChapterDownload> {
        return handler.awaitList {
            chapter_downloadsQueries.getByMangaId(mangaId, ChapterDownloadMapper::mapChapterDownload)
        }
    }

    override suspend fun getAll(): List<ChapterDownload> {
        return handler.awaitList {
            chapter_downloadsQueries.getAll(ChapterDownloadMapper::mapChapterDownload)
        }
    }
}
