package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.author.AuthorCatalogueSearch
import eu.kanade.tachiyomi.source.author.CatalogueCreditRole
import eu.kanade.tachiyomi.source.model.MangasPage
import exh.md.service.MangaDexAuthorIdResolver
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GlobalSearchScreenModel(
    initialQuery: String = "",
    initialExtensionFilter: String? = null,
    creditRole: CatalogueCreditRole? = null,
    authorIds: MangaDexAuthorIdResolver = MangaDexAuthorIdResolver(Injekt.get<NetworkHelper>().client),
) : SearchScreenModel(State(searchQuery = initialQuery)) {

    private val originalCredit = initialQuery.takeIf { creditRole != null }
    private var creditSearch = creditRole
    private val authorSearch = AuthorCatalogueSearch(authorIds::resolve)

    val activeCreditRole: CatalogueCreditRole?
        get() = creditSearch

    /**
     * A submitted query that differs from the tapped credit becomes a title search.
     */
    fun submitSearch(query: String) {
        if (creditSearch != null && query != originalCredit) {
            creditSearch = null
        }
        updateSearchQuery(query)
        search()
    }

    override fun searchModeKey(): Any? = creditSearch

    override suspend fun fetchSearchPage(
        source: Source,
        query: String,
        searchMode: Any?,
    ): MangasPage {
        val role = searchMode as? CatalogueCreditRole
            ?: return super.fetchSearchPage(source, query, searchMode)
        return authorSearch.search(source, query, page = 1, role = role)
    }

    init {
        extensionFilter = initialExtensionFilter
        if (initialQuery.isNotBlank() || !initialExtensionFilter.isNullOrBlank()) {
            if (extensionFilter != null) {
                // we're going to use custom extension filter instead
                setSourceFilter(SourceFilter.All)
            }
            search()
        }

        // KMK -->
        shouldPinnedSourcesHidden()
        // KMK <--
    }

    override fun getEnabledSources(): List<Source> {
        return super.getEnabledSources()
            .filter { state.value.sourceFilter != SourceFilter.PinnedOnly || "${it.id}" in pinnedSources }
    }
}
