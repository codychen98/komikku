package eu.kanade.tachiyomi.data.cloud

import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.ComicInfoPublishingStatus
import tachiyomi.domain.manga.model.Manga

// CLOUD -->
object CloudMangaComicInfo {
    fun fromManga(
        manga: Manga,
        categories: List<String>?,
        sourceName: String,
        mangaUrl: String?,
    ): ComicInfo {
        return ComicInfo(
            title = null,
            series = ComicInfo.Series(manga.ogTitle.ifBlank { manga.title }),
            number = null,
            summary = manga.description?.let { ComicInfo.Summary(it) },
            writer = manga.author?.let { ComicInfo.Writer(it) },
            penciller = manga.artist?.let { ComicInfo.Penciller(it) },
            inker = null,
            colorist = null,
            letterer = null,
            coverArtist = null,
            translator = null,
            genre = manga.genre?.let { ComicInfo.Genre(it.joinToString()) },
            tags = null,
            web = mangaUrl?.takeIf { it.isNotBlank() }?.let { ComicInfo.Web(it) },
            publishingStatus = ComicInfo.PublishingStatusTachiyomi(
                ComicInfoPublishingStatus.toComicInfoValue(manga.status),
            ),
            categories = categories?.takeIf { it.isNotEmpty() }
                ?.let { ComicInfo.CategoriesTachiyomi(it.joinToString()) },
            source = ComicInfo.SourceMihon(sourceName),
            padding = null,
        )
    }
}
// CLOUD <--
