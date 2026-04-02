package eu.kanade.tachiyomi.data.cache

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.domain.source.interactor.GetSourcesWithNonLibraryManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK -->
/**
 * Broadcast receiver that clears app caches and non-library manga from the database.
 *
 * Usage:
 * ```
 * adb shell am broadcast -a app.komikku.CLEAR_CACHE -n app.komikku/eu.kanade.tachiyomi.data.cache.ClearCacheBroadcastReceiver
 * ```
 */
class ClearCacheBroadcastReceiver : BroadcastReceiver() {

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CLEAR_CACHE) return

        val pendingResult = goAsync()

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val getSourcesWithNonLibraryManga: GetSourcesWithNonLibraryManga = Injekt.get()
                val database: Database = Injekt.get()
                val chapterCache: ChapterCache = Injekt.get()
                val pagePreviewCache: PagePreviewCache = Injekt.get()

                val sourceIds = getSourcesWithNonLibraryManga.subscribe().first().map { it.id }
                if (sourceIds.isNotEmpty()) {
                    database.mangasQueries.deleteNonLibraryManga(sourceIds, 0L)
                    database.historyQueries.removeResettedHistory()
                }

                chapterCache.clear()
                pagePreviewCache.clear()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to clear cache from broadcast intent" }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_CLEAR_CACHE = "app.komikku.CLEAR_CACHE"
    }
}
// KMK <--
