package eu.kanade.tachiyomi.ui.download

import android.content.Context
import eu.kanade.domain.chapter.interactor.RestoreOrphanedChapters
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

fun reindexDownloads(
    context: Context,
    scope: CoroutineScope,
    downloadCache: DownloadCache = Injekt.get(),
    restoreOrphanedChapters: RestoreOrphanedChapters = Injekt.get(),
) {
    downloadCache.invalidateCache()
    context.toast(MR.strings.download_cache_invalidated)
    scope.launchNonCancellable {
        val count = restoreOrphanedChapters.await()
        withUIContext {
            if (count > 0) {
                context.toast(
                    context.stringResource(
                        MR.strings.restore_orphaned_chapters_success,
                        count,
                    ),
                )
            }
        }
    }
}
