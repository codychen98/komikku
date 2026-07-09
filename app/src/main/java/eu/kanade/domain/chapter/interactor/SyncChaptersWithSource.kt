package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.isEhBasedManga
import tachiyomi.data.chapter.ChapterSanitizer
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal
import java.lang.Long.max
import java.time.ZonedDateTime
import java.util.TreeSet
import kotlin.math.abs

class SyncChaptersWithSource(
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterRepository: ChapterRepository,
    private val shouldUpdateDbChapter: ShouldUpdateDbChapter,
    private val updateManga: UpdateManga,
    private val updateChapter: UpdateChapter,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val libraryPreferences: LibraryPreferences,
) {

    /**
     * Method to synchronize db chapters with source ones
     *
     * @param rawSourceChapters the chapters from the source.
     * @param manga the manga the chapters belong to.
     * @param source the source the manga belongs to.
     * @return Newly added chapters
     */
    suspend fun await(
        rawSourceChapters: List<SChapter>,
        manga: Manga,
        source: Source,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
    ): List<Chapter> {
        if (rawSourceChapters.isEmpty() && !source.isLocal()) {
            throw NoChaptersException()
        }

        val now = ZonedDateTime.now()
        val nowMillis = now.toInstant().toEpochMilli()

        val sourceChapters = rawSourceChapters
            .distinctBy { it.url }
            .mapIndexed { i, sChapter ->
                Chapter.create()
                    .copyFromSChapter(sChapter)
                    .copy(name = with(ChapterSanitizer) { sChapter.name.sanitize(manga.title) })
                    .copy(mangaId = manga.id, sourceOrder = i.toLong())
            }

        val dbChapters = getChaptersByMangaId.await(manga.id)

        val newChapters = mutableListOf<Chapter>()
        val updatedChapters = mutableListOf<Chapter>()
        val reconciledDbChapterIdsToRemove = mutableSetOf<Long>()
        val removedByUrlChapters = dbChapters.filterNot { dbChapter ->
            sourceChapters.any { sourceChapter ->
                dbChapter.url == sourceChapter.url
            }
        }

        // Used to not set upload date of older chapters
        // to a higher value than newer chapters
        var maxSeenUploadDate = 0L

        for (sourceChapter in sourceChapters) {
            var chapter = sourceChapter

            // Update metadata from source if necessary.
            if (source is HttpSource) {
                val sChapter = chapter.toSChapter()
                source.prepareNewChapter(sChapter, manga.toSManga())
                chapter = chapter.copyFromSChapter(sChapter)
            }

            // Recognize chapter number for the chapter.
            val chapterNumber = ChapterRecognition.parseChapterNumber(
                manga.title,
                chapter.name,
                chapter.chapterNumber,
            )
            chapter = chapter.copy(chapterNumber = chapterNumber)

            val dbChapter = dbChapters.find { it.url == chapter.url }

            if (dbChapter == null) {
                // Reconcile against a previously retained chapter before inserting a new row.
                val reconciledMatch = removedByUrlChapters.find { candidate ->
                    candidate.id !in reconciledDbChapterIdsToRemove &&
                        isReconciliationCandidateMatch(candidate, chapter, manga)
                }
                if (reconciledMatch != null) {
                    chapter = chapter.copy(
                        read = reconciledMatch.read,
                        bookmark = reconciledMatch.bookmark,
                        lastPageRead = reconciledMatch.lastPageRead,
                        dateFetch = reconciledMatch.dateFetch,
                    )
                    reconciledDbChapterIdsToRemove.add(reconciledMatch.id)
                }
                val toAddChapter = if (chapter.dateUpload == 0L) {
                    val altDateUpload = if (maxSeenUploadDate == 0L) nowMillis else maxSeenUploadDate
                    chapter.copy(dateUpload = altDateUpload)
                } else {
                    maxSeenUploadDate = max(maxSeenUploadDate, sourceChapter.dateUpload)
                    chapter
                }
                newChapters.add(toAddChapter)
            } else {
                if (shouldUpdateDbChapter.await(dbChapter, chapter)) {
                    val shouldRenameChapter = downloadProvider.isChapterDirNameChanged(dbChapter, chapter) &&
                        downloadManager.isChapterDownloaded(
                            dbChapter.name,
                            dbChapter.scanlator,
                            dbChapter.url,
                            // SY -->
                            // manga.title,
                            manga.ogTitle,
                            // SY <--
                            manga.source,
                        )

                    if (shouldRenameChapter) {
                        downloadManager.renameChapter(source, manga, dbChapter, chapter)
                    }

                    var toChangeChapter = dbChapter.copy(
                        name = chapter.name,
                        chapterNumber = chapter.chapterNumber,
                        scanlator = chapter.scanlator,
                        sourceOrder = chapter.sourceOrder,
                    )

                    if (chapter.dateUpload != 0L) {
                        toChangeChapter = toChangeChapter.copy(dateUpload = chapter.dateUpload)
                    }
                    updatedChapters.add(toChangeChapter)
                }
            }
        }

        val removedChapters = removedByUrlChapters.filterNot { it.id in reconciledDbChapterIdsToRemove }

        // Return if there's nothing to add, delete, or update to avoid unnecessary db transactions.
        if (newChapters.isEmpty() && removedChapters.isEmpty() && updatedChapters.isEmpty()) {
            if (manualFetch || manga.fetchInterval == 0 || manga.nextUpdate < fetchWindow.first) {
                updateManga.awaitUpdateFetchInterval(
                    manga,
                    now,
                    fetchWindow,
                )
            }
            return emptyList()
        }

        val changedOrDuplicateReadUrls = mutableSetOf<String>()

        val deletedChapterNumbers = TreeSet<Double>()
        val deletedReadChapterNumbers = TreeSet<Double>()
        val deletedBookmarkedChapterNumbers = TreeSet<Double>()

        val readChapterNumbers = dbChapters
            .asSequence()
            .filter { it.read && it.isRecognizedNumber }
            .map { it.chapterNumber }
            .toSet()

        removedChapters.forEach { chapter ->
            if (chapter.read) deletedReadChapterNumbers.add(chapter.chapterNumber)
            if (chapter.bookmark) deletedBookmarkedChapterNumbers.add(chapter.chapterNumber)
            deletedChapterNumbers.add(chapter.chapterNumber)
        }

        val deletedChapterNumberDateFetchMap = removedChapters.sortedByDescending { it.dateFetch }
            .associate { it.chapterNumber to it.dateFetch }

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead().get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW)

        // Date fetch is set in such a way that the upper ones will have bigger value than the lower ones
        // Sources MUST return the chapters from most to less recent, which is common.
        var itemCount = newChapters.size
        var updatedToAdd = newChapters.map { toAddItem ->
            var chapter = toAddItem.copy(dateFetch = nowMillis + itemCount--)

            if (chapter.chapterNumber in readChapterNumbers && markDuplicateAsRead) {
                changedOrDuplicateReadUrls.add(chapter.url)
                chapter = chapter.copy(read = true)
            }

            if (!chapter.isRecognizedNumber || chapter.chapterNumber !in deletedChapterNumbers) return@map chapter

            chapter = chapter.copy(
                read = chapter.chapterNumber in deletedReadChapterNumbers,
                bookmark = chapter.chapterNumber in deletedBookmarkedChapterNumbers,
            )

            // Try to use the fetch date of the original entry to not pollute 'Updates' tab
            deletedChapterNumberDateFetchMap[chapter.chapterNumber]?.let {
                chapter = chapter.copy(dateFetch = it)
            }

            changedOrDuplicateReadUrls.add(chapter.url)

            chapter
        }

        // --> EXH (carry over reading progress)
        if (manga.isEhBasedManga()) {
            val hasNewChapters = updatedToAdd.any { it.url !in changedOrDuplicateReadUrls }
            if (hasNewChapters) {
                val max = dbChapters.maxOfOrNull { it.lastPageRead }
                if (max != null && max > 0) {
                    updatedToAdd = updatedToAdd.map {
                        if (it.url !in changedOrDuplicateReadUrls) {
                            it.copy(lastPageRead = max)
                        } else {
                            it
                        }
                    }
                }
            }
        }
        // <-- EXH

        if (removedChapters.isNotEmpty()) {
            val (downloadedRemovedChapters, notDownloadedRemovedChapters) = removedChapters.partition { chapter ->
                downloadManager.isChapterDownloaded(
                    chapterName = chapter.name,
                    chapterScanlator = chapter.scanlator,
                    chapterUrl = chapter.url,
                    mangaTitle = manga.ogTitle,
                    sourceId = manga.source,
                )
            }

            // Only delete chapters that are NOT downloaded
            if (notDownloadedRemovedChapters.isNotEmpty()) {
                val toDeleteIds = notDownloadedRemovedChapters.map { it.id }
                chapterRepository.removeChaptersWithIds(toDeleteIds)
            }

            val downloadedUnreadRemovedChapterUpdates = downloadedRemovedChapters
                .asSequence()
                .filterNot { it.read }
                .map { ChapterUpdate(id = it.id, read = true) }
                .toList()
            if (downloadedUnreadRemovedChapterUpdates.isNotEmpty()) {
                updateChapter.awaitAll(downloadedUnreadRemovedChapterUpdates)
            }
        }

        // Remove orphaned chapters that are now replaced by real source chapters
        if (reconciledDbChapterIdsToRemove.isNotEmpty()) {
            chapterRepository.removeChaptersWithIds(reconciledDbChapterIdsToRemove.toList())
        }

        if (updatedToAdd.isNotEmpty()) {
            updatedToAdd = chapterRepository.addAll(updatedToAdd)
        }

        // Place newly fetched chapters at the top when custom sort order is active
        if (updatedToAdd.isNotEmpty() && manga.sorting == Manga.CHAPTER_SORTING_CUSTOM) {
            val existingWithCustomOrder = dbChapters.filter { it.customSortOrder != null }
            if (existingWithCustomOrder.isNotEmpty()) {
                val newCount = updatedToAdd.size
                // Shift existing chapters down
                val shiftUpdates = existingWithCustomOrder.map { chapter ->
                    ChapterUpdate(
                        id = chapter.id,
                        customSortOrder = chapter.customSortOrder!! + newCount,
                    )
                }
                // Assign new chapters to top positions (0, 1, 2, ...)
                val newChapterUpdates = updatedToAdd.mapIndexed { index, chapter ->
                    ChapterUpdate(
                        id = chapter.id,
                        customSortOrder = index.toLong(),
                    )
                }
                updateChapter.awaitAll(shiftUpdates + newChapterUpdates)
            }
        }

        if (updatedChapters.isNotEmpty()) {
            val chapterUpdates = updatedChapters.map { it.toChapterUpdate() }
            updateChapter.awaitAll(chapterUpdates)
        }
        updateManga.awaitUpdateFetchInterval(manga, now, fetchWindow)

        // Set this manga as updated since chapters were changed
        // Note that last_update actually represents last time the chapter list changed at all
        updateManga.awaitUpdateLastUpdate(manga.id)

        // Scan download folder for orphaned chapters (exist on disk, missing from DB)
        if (!source.isLocal()) {
            val mangaDir = downloadProvider.findMangaDir(manga.ogTitle, source)
            if (mangaDir != null) {
                val allKnownChapters = dbChapters + updatedToAdd
                val knownFolderNames = allKnownChapters.flatMap { chapter ->
                    downloadProvider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                }.toHashSet()

                val orphanedChapters = mangaDir.listFiles().orEmpty()
                    .filter { file ->
                        val fileName = file.name ?: return@filter false
                        (file.isDirectory || fileName.endsWith(".cbz")) &&
                            fileName !in knownFolderNames &&
                            !fileName.endsWith(Downloader.TMP_DIR_SUFFIX)
                    }
                    .mapNotNull { dir ->
                        val fileName = dir.name ?: ""
                        val chapterName = if (fileName.endsWith(".cbz")) {
                            fileName.dropLast(4)
                        } else {
                            if (!dir.isDirectory) return@mapNotNull null
                            val resolved = downloadProvider.resolveChapterImageDir(dir)
                            if (!resolved.isValid) return@mapNotNull null
                            resolved.chapterName
                        }
                        val chapterNumber = ChapterRecognition.parseChapterNumber(
                            manga.title,
                            chapterName,
                            -1.0,
                        )
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "orphaned://$chapterName",
                            name = chapterName,
                            chapterNumber = chapterNumber,
                            sourceOrder = -1L,
                            dateFetch = dir.lastModified(),
                            dateUpload = 0L,
                        )
                    }

                if (orphanedChapters.isNotEmpty()) {
                    chapterRepository.addAll(orphanedChapters)
                }
            }
        }

        val excludedScanlators = getExcludedScanlators.await(manga.id).toHashSet()

        return updatedToAdd.filterNot { it.url in changedOrDuplicateReadUrls || it.scanlator in excludedScanlators }
    }

    private fun isReconciliationCandidateMatch(
        candidate: Chapter,
        incoming: Chapter,
        manga: Manga,
    ): Boolean {
        if (!hasCompatibleChapterNumber(candidate, incoming)) return false

        val isOrphanedCandidate = candidate.url.startsWith("orphaned://")
        val isDownloadedRetainedCandidate = downloadManager.isChapterDownloaded(
            chapterName = candidate.name,
            chapterScanlator = candidate.scanlator,
            chapterUrl = candidate.url,
            mangaTitle = manga.ogTitle,
            sourceId = manga.source,
        )
        if (!isOrphanedCandidate && !isDownloadedRetainedCandidate) return false

        if (!hasCompatibleScanlator(candidate.scanlator, incoming.scanlator)) return false

        // Keep number-based reconciliation strict to avoid incorrect merges.
        return hasCompatibleChapterName(candidate.name, incoming.name)
    }

    private fun hasCompatibleScanlator(existing: String?, incoming: String?): Boolean {
        if (existing.isNullOrBlank() || incoming.isNullOrBlank()) return true
        return existing.equals(incoming, ignoreCase = true)
    }

    private fun hasCompatibleChapterName(existing: String, incoming: String): Boolean {
        val existingNormalized = normalizeChapterNameForMatch(existing)
        val incomingNormalized = normalizeChapterNameForMatch(incoming)
        if (existingNormalized.isEmpty() || incomingNormalized.isEmpty()) return false

        if (existingNormalized == incomingNormalized) return true

        val existingDigits = Regex("\\d+").findAll(existing).map { it.value }.toSet()
        val incomingDigits = Regex("\\d+").findAll(incoming).map { it.value }.toSet()
        if (existingDigits.isNotEmpty() && incomingDigits.isNotEmpty() && existingDigits.intersect(incomingDigits).isNotEmpty()) {
            return true
        }

        val lengthDelta = abs(existingNormalized.length - incomingNormalized.length)
        return lengthDelta <= 8 &&
            (existingNormalized.contains(incomingNormalized) || incomingNormalized.contains(existingNormalized))
    }

    private fun hasCompatibleChapterNumber(candidate: Chapter, incoming: Chapter): Boolean {
        if (candidate.isRecognizedNumber && incoming.isRecognizedNumber) {
            return candidate.chapterNumber == incoming.chapterNumber
        }

        // Fallback for sources/folder names that break number recognition but still include chapter digits.
        val candidateTokens = extractNumberTokens(candidate.name)
        val incomingTokens = extractNumberTokens(incoming.name)
        return candidateTokens.isNotEmpty() &&
            incomingTokens.isNotEmpty() &&
            candidateTokens.intersect(incomingTokens).isNotEmpty()
    }

    private fun extractNumberTokens(value: String): Set<String> {
        return Regex("\\d+").findAll(value).map { it.value }.toSet()
    }

    private fun normalizeChapterNameForMatch(value: String): String {
        val withoutUrlHashSuffix = value
            .trim()
            .replace(Regex("_[a-f0-9]{6}$", RegexOption.IGNORE_CASE), "")
        return withoutUrlHashSuffix.lowercase().replace(Regex("[^a-z0-9]"), "")
    }
}
