package tachiyomi.domain.download.model

data class ChapterDownload(
    val chapterId: Long,
    val relativePath: String,
    val linkedAt: Long,
)
