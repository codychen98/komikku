package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.author.AuthorCatalogueSearch
import eu.kanade.tachiyomi.source.author.CatalogueCreditRole
import eu.kanade.tachiyomi.source.model.MangasPage
import exh.md.service.MangaDexAuthorIdResolver
import tachiyomi.data.source.BaseSourcePagingSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AuthorCreditPagingSource(
    source: Source,
    private val credit: String,
    private val role: CatalogueCreditRole,
    authorIds: MangaDexAuthorIdResolver = MangaDexAuthorIdResolver(Injekt.get<NetworkHelper>().client),
) : BaseSourcePagingSource(source) {

    private val search = AuthorCatalogueSearch(authorIds::resolve)

    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return search.search(source, credit, currentPage, role)
    }
}
