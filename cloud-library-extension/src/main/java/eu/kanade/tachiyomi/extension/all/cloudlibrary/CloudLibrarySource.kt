package eu.kanade.tachiyomi.extension.all.cloudlibrary

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.all.cloudlibrary.cloudstorage.CloudLibraryIds
import eu.kanade.tachiyomi.extension.all.cloudlibrary.cloudstorage.CloudPathUtil
import eu.kanade.tachiyomi.extension.all.cloudlibrary.cloudstorage.CloudStorageClient
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * Shared Cloud Library browse/read behavior. Subclasses supply the storage client and prefs UI.
 * Uses fetch* Observables for extensions-lib 1.4 compatibility (app bridges via awaitSingle).
 */
abstract class CloudLibrarySource : HttpSource(), ConfigurableSource {

    override val lang = "all"
    override val supportsLatest = true
    override val baseUrl: String = "cloud://library"

    protected abstract fun createClient(): CloudStorageClient

    protected fun preferences(): SharedPreferences =
        Injekt.get<Application>().getSharedPreferences("source_$id", Context.MODE_PRIVATE)

    protected open fun libraryRoot(): String {
        val folder = preferences().getString(PREF_FOLDER, "")?.trim('/').orEmpty()
        return if (folder.isEmpty()) {
            CloudLibraryIds.LIBRARY_ROOT
        } else {
            CloudPathUtil.join(folder, CloudLibraryIds.LIBRARY_ROOT)
        }
    }

    private fun clientOrThrow(): CloudStorageClient = createClient()

    override fun headersBuilder(): Headers.Builder {
        val builder = super.headersBuilder()
        createClient().authHeaders().forEach { (k, v) -> builder.set(k, v) }
        return builder
    }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val auth = createClient().authHeaders()
                if (auth.isEmpty()) {
                    chain.proceed(original)
                } else {
                    val req = original.newBuilder().apply {
                        auth.forEach { (k, v) -> header(k, v) }
                    }.build()
                    chain.proceed(req)
                }
            }
            .build()
    }

    @Deprecated("Use the suspend API instead")
    override fun fetchPopularManga(page: Int): Observable<MangasPage> =
        Observable.fromCallable { runBlocking { listManga(query = null, latest = false) } }

    @Deprecated("Use the suspend API instead")
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
        Observable.fromCallable { runBlocking { listManga(query = null, latest = true) } }

    @Deprecated("Use the suspend API instead")
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.fromCallable { runBlocking { listManga(query = query, latest = false) } }

    @Deprecated("Use the combined suspend API instead")
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        Observable.fromCallable { runBlocking { mangaDetails(manga) } }

    @Deprecated("Use the combined suspend API instead")
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        Observable.fromCallable { runBlocking { chapterList(manga) } }

    @Deprecated("Use the suspend API instead")
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        Observable.fromCallable { runBlocking { pageList(chapter) } }

    private suspend fun listManga(query: String?, latest: Boolean): MangasPage {
        val root = libraryRoot()
        val entries = clientOrThrow().list(root).filter { it.isDir }
        val filtered = if (query.isNullOrBlank()) {
            entries
        } else {
            entries.filter { it.name.contains(query, ignoreCase = true) }
        }
        val sorted = if (latest) {
            filtered.sortedByDescending { it.mtime }
        } else {
            filtered.sortedBy { it.name.lowercase(Locale.ROOT) }
        }
        val mangas = sorted.map { entry ->
            SManga.create().apply {
                url = entry.name
                title = entry.name
                thumbnail_url = clientOrThrow().fileUrl(CloudPathUtil.join(root, entry.name, "cover.jpg"))
            }
        }
        return MangasPage(mangas, false)
    }

    private suspend fun mangaDetails(manga: SManga): SManga {
        val root = libraryRoot()
        val dir = manga.url
        val comicPath = CloudPathUtil.join(root, dir, "ComicInfo.xml")
        val details = SManga.create().apply {
            url = manga.url
            title = manga.title
            thumbnail_url = clientOrThrow().fileUrl(CloudPathUtil.join(root, dir, "cover.jpg"))
        }
        return try {
            clientOrThrow().downloadFile(comicPath).use { stream ->
                val info = ComicInfoParser.parse(stream)
                details.apply {
                    title = info.series ?: dir
                    description = info.summary
                    author = info.writer
                    artist = info.penciller ?: info.writer
                    genre = listOfNotNull(info.genre, info.categories)
                        .flatMap { it.split(',', ';') }
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .joinToString(", ")
                        .ifEmpty { null }
                    status = when (info.status?.lowercase(Locale.ROOT)) {
                        "ongoing" -> SManga.ONGOING
                        "completed" -> SManga.COMPLETED
                        "licensed" -> SManga.LICENSED
                        "publishing finished", "publishingfinished" -> SManga.PUBLISHING_FINISHED
                        "cancelled" -> SManga.CANCELLED
                        "on hiatus", "onhiatus" -> SManga.ON_HIATUS
                        else -> SManga.UNKNOWN
                    }
                    initialized = true
                }
            }
        } catch (_: Exception) {
            details.apply {
                title = dir
                initialized = true
            }
        }
    }

    private suspend fun chapterList(manga: SManga): List<SChapter> {
        val root = libraryRoot()
        val mangaPath = CloudPathUtil.join(root, manga.url)
        val dirs = clientOrThrow().list(mangaPath).filter { it.isDir }
        return dirs.map { entry ->
            val chapterUrl = CloudPathUtil.join(manga.url, entry.name)
            var chapterName = entry.name
            var chapterNumber = -1f
            try {
                clientOrThrow().downloadFile(CloudPathUtil.join(mangaPath, entry.name, "ComicInfo.xml"))
                    .use { stream ->
                        val info = ComicInfoParser.parse(stream)
                        if (!info.title.isNullOrBlank()) {
                            chapterName = info.title!!
                        }
                        info.number?.toFloatOrNull()?.let { chapterNumber = it }
                    }
            } catch (_: Exception) {
                // keep dirname
            }
            SChapter.create().apply {
                url = chapterUrl
                name = chapterName
                date_upload = entry.mtime
                chapter_number = chapterNumber
            }
        }.sortedByDescending { chapter ->
            chapter.chapter_number.takeIf { n -> n >= 0 } ?: Float.MIN_VALUE
        }
    }

    private suspend fun pageList(chapter: SChapter): List<Page> {
        val root = libraryRoot()
        val chapterPath = CloudPathUtil.join(root, chapter.url)
        val images = clientOrThrow().list(chapterPath)
            .filter { !it.isDir && isImage(it.name) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        return images.mapIndexed { index, entry ->
            val path = CloudPathUtil.join(chapterPath, entry.name)
            Page(index, imageUrl = pageImageUrl(path))
        }
    }

    protected open suspend fun pageImageUrl(path: String): String = clientOrThrow().fileUrl(path)

    override fun getMangaUrl(manga: SManga): String =
        createClient().fileUrl(CloudPathUtil.join(libraryRoot(), manga.url))

    override fun getChapterUrl(chapter: SChapter): String =
        createClient().fileUrl(CloudPathUtil.join(libraryRoot(), chapter.url))

    // Unused request/parse stubs required by HttpSource
    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()
    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun fetchImageUrl(page: Page): Observable<String> =
        Observable.just(page.imageUrl ?: "")

    override fun imageRequest(page: Page): Request {
        val url = page.imageUrl ?: throw IllegalStateException("Missing imageUrl")
        return GET(url, headers)
    }

    protected fun addFolderPreference(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_FOLDER
            title = "Remote folder"
            summary = "Folder that contains komikku-library/ (optional)"
            setDefaultValue("")
            dialogTitle = title
        }.also(screen::addPreference)
    }

    companion object {
        const val PREF_FOLDER = "folder"
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")

        fun isImage(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return ext in IMAGE_EXT
        }
    }
}
