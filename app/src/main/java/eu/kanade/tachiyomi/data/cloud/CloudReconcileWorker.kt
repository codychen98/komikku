package eu.kanade.tachiyomi.data.cloud

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import logcat.LogPriority
import mihon.core.cloudstorage.CloudLibraryIds
import mihon.core.cloudstorage.CloudPathUtil
import mihon.domain.manga.model.toDomainManga
import mihon.domain.migration.usecases.MigrateMangaUseCase
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.CloudStorageMode
import tachiyomi.domain.storage.service.CloudStoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream

// CLOUD -->
/**
 * Moves remote trees previously stored as downloads/<Source>/<Manga>/<chapters>
 * into komikku-library/<MangaDirName>/, backfills cover + manga ComicInfo, and migrates matches.
 */
class CloudReconcileWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val cloudPreferences: CloudStoragePreferences = Injekt.get()
    private val getLibraryManga: GetLibraryManga = Injekt.get()
    private val getManga: GetManga = Injekt.get()
    private val downloadProvider: DownloadProvider = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val coverCache: CoverCache = Injekt.get()
    private val getCategories: GetCategories = Injekt.get()
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get()
    private val migrateManga: MigrateMangaUseCase = Injekt.get()
    private val xml: XML = Injekt.get()
    private val notifier = CloudUploadNotifier(context)

    override suspend fun doWork(): Result {
        if (cloudPreferences.mode().get() == CloudStorageMode.OFF) {
            return Result.failure()
        }

        return try {
            val client = CloudStorageClientFactory.create(cloudPreferences)
            val library = getLibraryManga.await()
            val byDirName = library.associateBy { downloadProvider.getMangaDirName(it.manga.ogTitle) }

            val downloadsRoot = "downloads"
            val sourceDirs = try {
                client.list(downloadsRoot).filter { it.isDir }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "No downloads/ root on remote" }
                emptyList()
            }

            var adopted = 0
            val unmatched = mutableListOf<String>()

            for (sourceDir in sourceDirs) {
                val sourcePath = CloudPathUtil.join(downloadsRoot, sourceDir.name)
                val mangaDirs = client.list(sourcePath).filter { it.isDir }
                for (mangaDir in mangaDirs) {
                    val mangaPath = CloudPathUtil.join(sourcePath, mangaDir.name)
                    val libEntry = byDirName[mangaDir.name]
                    val targetPath = CloudPathUtil.join(CloudLibraryIds.LIBRARY_ROOT, mangaDir.name)

                    // Move manga tree into komikku-library
                    if (!client.exists(targetPath)) {
                        client.mkdirs(CloudLibraryIds.LIBRARY_ROOT)
                        client.move(mangaPath, targetPath)
                    }

                    if (libEntry != null) {
                        backfillMetadata(client, libEntry, targetPath)
                        if (cloudPreferences.autoMigrate().get() &&
                            libEntry.manga.source !in CloudLibraryIds.ALL
                        ) {
                            migrateLibraryEntry(libEntry, mangaDir.name)
                        }
                        adopted++
                    } else {
                        unmatched.add(mangaDir.name)
                    }
                }
            }

            notifier.showReconcileComplete(adopted, unmatched.size)
            if (unmatched.isNotEmpty()) {
                logcat(LogPriority.WARN) {
                    "Cloud adopt unmatched dirs: ${unmatched.joinToString()}"
                }
            }
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Cloud reconcile failed" }
            notifier.showReconcileError(e.message ?: e.toString())
            Result.failure()
        }
    }

    private suspend fun backfillMetadata(
        client: mihon.core.cloudstorage.CloudStorageClient,
        libEntry: LibraryManga,
        mangaRemoteRoot: String,
    ) {
        val manga = libEntry.manga
        val source = sourceManager.get(manga.source)

        val remoteCover = CloudPathUtil.join(mangaRemoteRoot, "cover.jpg")
        if (!client.exists(remoteCover)) {
            val coverFile = coverCache.getCustomCoverFile(manga.id).takeIf { it.exists() }
                ?: coverCache.getCoverFile(manga.thumbnailUrl)?.takeIf { it.exists() }
            if (coverFile != null) {
                coverFile.inputStream().use { stream ->
                    client.uploadFile(remoteCover, stream, coverFile.length())
                }
            }
        }

        val remoteComic = CloudPathUtil.join(mangaRemoteRoot, COMIC_INFO_FILE)
        if (!client.exists(remoteComic)) {
            val categories = getCategories.await(manga.id).map { it.name.trim() }
            val mangaUrl = (source as? HttpSource)?.getMangaUrl(
                eu.kanade.tachiyomi.source.model.SManga.create().apply {
                    url = manga.url
                    title = manga.title
                },
            )
            val comicInfo = CloudMangaComicInfo.fromManga(
                manga = manga,
                categories = categories,
                sourceName = source?.name ?: "",
                mangaUrl = mangaUrl,
            )
            val bytes = xml.encodeToString(ComicInfo.serializer(), comicInfo).toByteArray()
            ByteArrayInputStream(bytes).use { stream ->
                client.uploadFile(remoteComic, stream, bytes.size.toLong())
            }
        }
    }

    private suspend fun migrateLibraryEntry(libEntry: LibraryManga, mangaDirName: String) {
        val mode = cloudPreferences.mode().get()
        val sourceId = CloudStorageClientFactory.sourceIdForMode(mode) ?: return
        val cloudSource = sourceManager.get(sourceId) as? CatalogueSource
        if (cloudSource == null) {
            notifier.showMigrateSkipped()
            return
        }
        val page = cloudSource.getSearchManga(1, mangaDirName, FilterList())
        val match = page.mangas.firstOrNull {
            it.url == mangaDirName || it.title == mangaDirName
        } ?: return
        val target = networkToLocalManga(match.toDomainManga(sourceId))
        migrateManga(current = libEntry.manga, target = target, replace = true)
        val migrated = getManga.await(target.id)
        if (migrated == null || !migrated.favorite || migrated.source != sourceId) {
            throw IllegalStateException("Migrate to cloud source did not complete for $mangaDirName")
        }
    }

    companion object {
        private const val WORK_NAME = "CloudReconcile"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<CloudReconcileWorker>()
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
// CLOUD <--
