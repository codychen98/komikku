package eu.kanade.tachiyomi.source.author

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.all.MangaDex
import exh.source.anyIs
import exh.source.getOriginalSource
import exh.source.isMdBasedSource
import kotlinx.coroutines.CancellationException

/**
 * Query prefix the MangaDex extension already accepts.
 * It is sent as `authorOrArtist` on the MangaDex API.
 */
internal const val MANGADEX_AUTHOR_QUERY_PREFIX = "author:"
private const val MANGADEX_SOURCE_NAME = "MangaDex"

/**
 * Loads titles for one credit line.
 * Person filters and MangaDex ids are queried once per name, then merged.
 * Sources with neither keep a title search.
 */
class AuthorCatalogueSearch(
    private val resolveMangaDexAuthorId: suspend (String) -> String?,
) {
    suspend fun search(
        source: Source,
        credit: String,
        page: Int,
        role: CatalogueCreditRole,
    ): MangasPage {
        val names = authorCreditNames(credit)
        if (names.isEmpty()) {
            return MangasPage(emptyList(), false)
        }
        return if (source.usesMangaDexAuthorQuery()) {
            searchMangaDex(source, names, page)
        } else {
            searchWithCreditFilter(source, credit, names, page, role)
        }
    }

    private suspend fun searchMangaDex(
        source: Source,
        names: List<String>,
        page: Int,
    ): MangasPage {
        val attempts = names.map { name ->
            runCatchingRequest<MangasPage?> {
                val authorId = resolveMangaDexAuthorId(name)
                    ?: return@runCatchingRequest null
                source.getSearchManga(
                    page,
                    MANGADEX_AUTHOR_QUERY_PREFIX + authorId,
                    source.getFilterList(),
                )
            }
        }
        return finish(attempts)
    }

    private suspend fun searchWithCreditFilter(
        source: Source,
        credit: String,
        names: List<String>,
        page: Int,
        role: CatalogueCreditRole,
    ): MangasPage {
        val probe = source.getFilterList()
        if (findCreditFilter(probe, role) == null) {
            return source.getSearchManga(page, credit, probe)
        }
        val attempts = names.map { name ->
            runCatchingRequest<MangasPage?> {
                val filters = source.getFilterList()
                val filter = findCreditFilter(filters, role)
                    ?: return@runCatchingRequest source.getSearchManga(page, credit, filters)
                // Filter.Text only accepts a new value by assigning state.
                // This list comes from a fresh getFilterList() call.
                filter.state = name
                source.getSearchManga(page, "", filters)
            }
        }
        return finish(attempts)
    }

    private fun finish(attempts: List<Result<MangasPage?>>): MangasPage {
        val pages = attempts.mapNotNull { it.getOrNull() }
        if (pages.isNotEmpty()) {
            return mergeAuthorPages(pages)
        }
        attempts.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        return MangasPage(emptyList(), false)
    }
}

private fun Source.usesMangaDexAuthorQuery(): Boolean {
    if (isMdBasedSource() || anyIs<MangaDex>()) return true
    return name.equals(MANGADEX_SOURCE_NAME, ignoreCase = true) ||
        getOriginalSource().name.equals(MANGADEX_SOURCE_NAME, ignoreCase = true)
}

private suspend fun <T> runCatchingRequest(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
