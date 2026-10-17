package eu.kanade.tachiyomi.data.cloud

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.delay
import logcat.LogPriority
import mihon.core.cloudstorage.CloudLibraryIds
import mihon.core.cloudstorage.CloudPathUtil
import mihon.core.cloudstorage.CloudStorageClient
import mihon.domain.manga.model.toDomainManga
import mihon.domain.migration.usecases.MigrateMangaUseCase
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.CloudStorageMode
import tachiyomi.domain.storage.service.CloudStoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

// CLOUD -->
class CloudUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val cloudPreferences: CloudStoragePreferences = Injekt.get()
    private val getManga: GetManga = Injekt.get()
    private val getChapter: GetChapter = Injekt.get()
    private val downloadProvider: DownloadProvider = Injekt.get()
    private val downloadManager: DownloadManager = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val coverCache: CoverCache = Injekt.get()
    private val getCategories: GetCategories = Injekt.get()
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get()
    private val migrateManga: MigrateMangaUseCase = Injekt.get()
    private val xml: XML = Injekt.get()
    private val notifier = CloudUploadNotifier(context)

    override suspend fun doWork(): Result {
        val mangaId = inputData.getLong(KEY_MANGA_ID, -1L)
        val chapterId = inputData.getLong(KEY_CHAPTER_ID, -1L)
        if (mangaId < 0 || chapterId < 0) return Result.failure()

        if (cloudPreferences.mode().get() == CloudStorageMode.OFF) {
            return Result.success()
        }

        val manga = getManga.await(mangaId) ?: return Result.failure()
        val chapter = getChapter.await(chapterId) ?: return Result.failure()
        val source = sourceManager.get(manga.source) ?: return Result.failure()

        notifier.showProgress(chapter.name)

        return try {
            val client = CloudStorageClientFactory.create(cloudPreferences)
            val mangaDirName = downloadProvider.getMangaDirName(manga.ogTitle)
            val chapterDir = downloadProvider.findChapterDir(
                chapter.name,
                chapter.scanlator,
                chapter.url,
                manga.ogTitle,
                source,
            ) ?: throw IllegalStateException("Chapter directory not found for ${chapter.name}")

            if (!chapterDir.isDirectory) {
                throw IllegalStateException("Cloud upload requires plain folder chapters (not CBZ)")
            }

            val mangaRemoteRoot = CloudPathUtil.join(CloudLibraryIds.LIBRARY_ROOT, mangaDirName)
            client.mkdirs(mangaRemoteRoot)

            uploadCoverIfNeeded(client, manga, mangaRemoteRoot)
            uploadMangaComicInfoIfNeeded(client, manga, source, mangaRemoteRoot)

            val chapterDirName = chapterDir.name
                ?: downloadProvider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url)
            val chapterRemotePath = CloudPathUtil.join(mangaRemoteRoot, chapterDirName)
            client.mkdirs(chapterRemotePath)

            uploadChapterFiles(client, chapterDir, chapterRemotePath)
            verifyUpload(client, chapterDir, chapterRemotePath)

            var migrateOk = true
            if (cloudPreferences.autoMigrate().get() && manga.source !in CloudLibraryIds.ALL) {
                migrateOk = migrateToCloud(manga, mangaDirName)
            }

            if (migrateOk && cloudPreferences.deleteLocalAfterUpload().get()) {
                downloadManager.deleteChapters(
                    chapters = listOf(chapter),
                    manga = manga,
                    source = source,
                    ignoreCategoryExclusion = true,
                )
                // deleteChapters is fire-and-forget via launchIO; give it a moment
                delay(500)
            }

            notifier.dismissProgress()
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Cloud upload failed" }
            notifier.showError(e.message ?: e.toString())
            notifier.dismissProgress()
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    private suspend fun uploadCoverIfNeeded(
        client: CloudStorageClient,
        manga: tachiyomi.domain.manga.model.Manga,
        mangaRemoteRoot: String,
    ) {
        val remoteCover = CloudPathUtil.join(mangaRemoteRoot, "cover.jpg")
        if (client.exists(remoteCover)) {
            val remote = client.list(mangaRemoteRoot).find { it.name == "cover.jpg" }
            if (remote != null && remote.size > 0) return
        }
        val coverFile = coverCache.getCustomCoverFile(manga.id).takeIf { it.exists() }
            ?: coverCache.getCoverFile(manga.thumbnailUrl)?.takeIf { it.exists() }
            ?: return
        coverFile.inputStream().use { stream ->
            client.uploadFile(remoteCover, stream, coverFile.length())
        }
    }

    private suspend fun uploadMangaComicInfoIfNeeded(
        client: CloudStorageClient,
        manga: tachiyomi.domain.manga.model.Manga,
        source: eu.kanade.tachiyomi.source.Source,
        mangaRemoteRoot: String,
    ) {
        val remotePath = CloudPathUtil.join(mangaRemoteRoot, COMIC_INFO_FILE)
        if (client.exists(remotePath)) {
            val remote = client.list(mangaRemoteRoot).find { it.name == COMIC_INFO_FILE }
            if (remote != null && remote.size > 0) return
        }
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
            sourceName = source.name,
            mangaUrl = mangaUrl,
        )
        val bytes = xml.encodeToString(ComicInfo.serializer(), comicInfo).toByteArray()
        ByteArrayInputStream(bytes).use { stream ->
            client.uploadFile(remotePath, stream, bytes.size.toLong())
        }
    }

    private suspend fun uploadChapterFiles(
        client: CloudStorageClient,
        chapterDir: UniFile,
        chapterRemotePath: String,
    ) {
        val remoteFiles = client.list(chapterRemotePath).associateBy { it.name }
        val localFiles = chapterDir.listFiles().orEmpty().filter { it.isFile && !it.name.isNullOrBlank() }
        for (file in localFiles) {
            val name = file.name!!
            val length = file.length()
            val existing = remoteFiles[name]
            if (existing != null && existing.size == length) continue
            file.openInputStream().use { stream ->
                client.uploadFile(CloudPathUtil.join(chapterRemotePath, name), stream, length)
            }
        }
    }

    private suspend fun verifyUpload(
        client: CloudStorageClient,
        chapterDir: UniFile,
        chapterRemotePath: String,
    ) {
        val localFiles = chapterDir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.isNullOrBlank() }
            .associate { it.name!! to it.length() }
        val remoteFiles = client.list(chapterRemotePath)
            .filter { !it.isDir }
            .associate { it.name to it.size }

        if (localFiles.size != remoteFiles.size) {
            throw IllegalStateException(
                "Verify failed: local=${localFiles.size} remote=${remoteFiles.size} files",
            )
        }
        for ((name, size) in localFiles) {
            val remoteSize = remoteFiles[name]
                ?: throw IllegalStateException("Verify failed: missing remote file $name")
            if (remoteSize != size) {
                throw IllegalStateException("Verify failed: size mismatch for $name ($remoteSize != $size)")
            }
        }
    }

    private suspend fun migrateToCloud(
        manga: tachiyomi.domain.manga.model.Manga,
        mangaDirName: String,
    ): Boolean {
        val mode = cloudPreferences.mode().get()
        val sourceId = CloudStorageClientFactory.sourceIdForMode(mode) ?: return false
        val cloudSource = sourceManager.get(sourceId) as? CatalogueSource
        if (cloudSource == null) {
            notifier.showMigrateSkipped()
            return false
        }

        val page = cloudSource.getSearchManga(1, mangaDirName, FilterList())
        val match = page.mangas.firstOrNull { sManga ->
            sManga.url == mangaDirName || sManga.title == mangaDirName
        } ?: page.mangas.firstOrNull {
            downloadProvider.getMangaDirName(it.title) == mangaDirName
        }

        if (match == null) {
            throw IllegalStateException("Cloud manga not found after upload: $mangaDirName")
        }

        val target = networkToLocalManga(match.toDomainManga(sourceId))
        migrateManga(current = manga, target = target, replace = true)
        // MigrateMangaUseCase swallows errors; confirm the target was favorited.
        val migrated = getManga.await(target.id)
        if (migrated == null || !migrated.favorite || migrated.source != sourceId) {
            throw IllegalStateException("Migrate to cloud source did not complete for $mangaDirName")
        }
        return true
    }

    companion object {
        private const val WORK_NAME = "CloudUpload"
        private const val KEY_MANGA_ID = "manga_id"
        private const val KEY_CHAPTER_ID = "chapter_id"

        fun enqueue(context: Context, mangaId: Long, chapterId: Long) {
            val request = OneTimeWorkRequestBuilder<CloudUploadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_MANGA_ID to mangaId,
                        KEY_CHAPTER_ID to chapterId,
                    ),
                )
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS,
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND, request)
        }
    }
}
// CLOUD <--
