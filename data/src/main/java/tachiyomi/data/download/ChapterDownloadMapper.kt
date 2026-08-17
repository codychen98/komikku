package tachiyomi.data.download

import tachiyomi.domain.download.model.ChapterDownload

object ChapterDownloadMapper {

    fun mapChapterDownload(
        chapterId: Long,
        relativePath: String,
        linkedAt: Long,
    ): ChapterDownload = ChapterDownload(
        chapterId = chapterId,
        relativePath = relativePath,
        linkedAt = linkedAt,
    )
}
