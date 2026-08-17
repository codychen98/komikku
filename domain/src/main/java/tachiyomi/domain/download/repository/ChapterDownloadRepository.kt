package tachiyomi.domain.download.repository

import tachiyomi.domain.download.model.ChapterDownload

interface ChapterDownloadRepository {

    suspend fun insert(chapterDownload: ChapterDownload)

    suspend fun upsert(chapterDownload: ChapterDownload)

    suspend fun deleteByChapterId(chapterId: Long)

    suspend fun getByChapterId(chapterId: Long): ChapterDownload?

    suspend fun getByMangaId(mangaId: Long): List<ChapterDownload>

    suspend fun getAll(): List<ChapterDownload>
}
