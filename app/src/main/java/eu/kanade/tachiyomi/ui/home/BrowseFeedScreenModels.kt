package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.staticCompositionLocalOf
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteScreenModel
import eu.kanade.tachiyomi.ui.browse.feed.FeedScreenModel

/**
 * [FeedScreenModel] and [BulkFavoriteScreenModel] are provided from [HomeScreen] so they survive
 * bottom-tab switches (AnimatedContent disposing [eu.kanade.tachiyomi.ui.browse.BrowseTab]).
 *
 * Only compose [eu.kanade.tachiyomi.ui.browse.BrowseTab] under this provider.
 */
val LocalFeedScreenModel = staticCompositionLocalOf<FeedScreenModel> {
    error("LocalFeedScreenModel missing: BrowseTab must be composed under HomeScreen.")
}

val LocalBulkFavoriteScreenModel = staticCompositionLocalOf<BulkFavoriteScreenModel> {
    error("LocalBulkFavoriteScreenModel missing: BrowseTab must be composed under HomeScreen.")
}
