package eu.kanade.tachiyomi.ui.download

import android.content.Context
import eu.kanade.domain.chapter.interactor.CleanupOrphanedDuplicateChapters
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

/**
 * Invalidates the download cache, then runs [RestoreOrphanedChapters] and [CleanupOrphanedDuplicateChapters].
 * Restore scans on-disk chapter folders; any folder name not recognized from current DB chapters may
 * insert synthetic rows (`orphaned://` URLs). Cleanup removes existing false orphan rows when
 * `ComicInfo.xml` uniquely matches a catalog chapter. Chapter directory basenames are derived from
 * [eu.kanade.tachiyomi.data.download.DownloadProvider.getChapterDirName], which can append a URL
 * hash suffix when enabled in download preferences; if a chapter's stored `url` changes, the
 * on-disk folder may no longer match and false orphans can appear. See
 * `roadmap/duplicated chapters/orphan_chapter_duplicates_implementation.md`.
 */
fun reindexDownloads(
    context: Context,
    scope: CoroutineScope,
    downloadCache: DownloadCache = Injekt.get(),
    restoreOrphanedChapters: RestoreOrphanedChapters = Injekt.get(),
    cleanupOrphanedDuplicateChapters: CleanupOrphanedDuplicateChapters = Injekt.get(),
) {
    downloadCache.invalidateCache()
    context.toast(MR.strings.download_cache_invalidated)
    scope.launchNonCancellable {
        val restored = restoreOrphanedChapters.await()
        val removedDuplicates = cleanupOrphanedDuplicateChapters.await()
        withUIContext {
            if (restored > 0) {
                context.toast(
                    context.stringResource(
                        MR.strings.restore_orphaned_chapters_success,
                        restored,
                    ),
                )
            }
            if (removedDuplicates > 0) {
                context.toast(
                    context.stringResource(
                        MR.strings.cleanup_duplicate_orphan_chapters_success,
                        removedDuplicates,
                    ),
                )
            }
        }
    }
}
