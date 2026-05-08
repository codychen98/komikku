package eu.kanade.domain.manga.interactor

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.Downloader
import tachiyomi.domain.source.service.SourceManager
import exh.source.MERGED_SOURCE_ID
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ReindexMergeManga(
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) {

    fun preflight(selectedManga: List<Manga>, parentId: Long): PreflightResult {
        if (selectedManga.size < 2) {
            return PreflightResult.Failure("Select at least 2 manga to reindex merge.")
        }

        val parent = selectedManga.firstOrNull { it.id == parentId }
            ?: return PreflightResult.Failure("Selected parent must be part of the current selection.")

        val children = selectedManga.filter { it.id != parentId }
        if (children.isEmpty()) {
            return PreflightResult.Failure("At least 1 child manga is required.")
        }

        val unsupported = selectedManga.filter { it.source == LocalSource.ID || it.source == MERGED_SOURCE_ID }
        if (unsupported.isNotEmpty()) {
            return if (unsupported.size == selectedManga.size) {
                PreflightResult.Failure("Selected manga are unsupported for Reindex Merge (local/merged source).")
            } else {
                PreflightResult.Partial(
                    parent = parent,
                    children = children,
                    unsupported = unsupported,
                    message = "Some selected manga are unsupported (local/merged source) and may not be mergeable.",
                )
            }
        }

        return PreflightResult.Success(
            parent = parent,
            children = children,
            message = "Ready to reindex merge.",
        )
    }

    sealed interface PreflightResult {
        data class Success(
            val parent: Manga,
            val children: List<Manga>,
            val message: String,
        ) : PreflightResult

        data class Partial(
            val parent: Manga,
            val children: List<Manga>,
            val unsupported: List<Manga>,
            val message: String,
        ) : PreflightResult

        data class Failure(
            val message: String,
        ) : PreflightResult
    }

    suspend fun moveChildDownloads(parent: Manga, children: List<Manga>): MoveResult {
        val parentSource = sourceManager.getOrStub(parent.source)
        val parentDir = downloadProvider.findMangaDir(parent.ogTitle, parentSource)
            ?: downloadProvider.getMangaDir(parent.ogTitle, parentSource).getOrNull()
            ?: return MoveResult.Failure("Could not resolve parent download directory.")

        val report = children.fold(MoveReport()) { acc, child ->
            moveSingleChild(parentDir, child, acc)
        }

        return if (report.errors.isEmpty()) {
            MoveResult.Success(report)
        } else {
            MoveResult.Partial(report)
        }
    }

    private fun moveSingleChild(parentDir: UniFile, child: Manga, initialReport: MoveReport): MoveReport {
        var hasBlockingIssue = false
        val childSource = sourceManager.getOrStub(child.source)
        val childDir = downloadProvider.findMangaDir(child.ogTitle, childSource)
            ?: return initialReport.copy(
                fullyMergedChildIds = initialReport.fullyMergedChildIds + child.id,
            )

        val childEntries = childDir.listFiles()
            .orEmpty()
            .filterNot { entry -> entry.name?.endsWith(Downloader.TMP_DIR_SUFFIX) == true }

        val movedReport = childEntries.fold(initialReport) { acc, entry ->
            val sourceName = entry.name ?: return@fold acc.copy(
                skipped = acc.skipped + 1,
                skippedEntries = acc.skippedEntries + "${child.title}: unnamed entry",
            ).also { hasBlockingIssue = true }
            val targetName = getTargetName(parentDir, sourceName, child.title)
            val renamed = targetName != sourceName
            when {
                parentDir.findFile(targetName) != null -> {
                    hasBlockingIssue = true
                    acc.copy(
                        skipped = acc.skipped + 1,
                        skippedEntries = acc.skippedEntries + "${child.title}: collision unresolved for $sourceName",
                    )
                }
                !copyEntry(entry, parentDir, targetName) -> {
                    hasBlockingIssue = true
                    acc.copy(
                        errors = acc.errors + "${child.title}: failed to move $sourceName",
                    )
                }
                !entry.delete() -> {
                    hasBlockingIssue = true
                    acc.copy(
                        moved = acc.moved + 1,
                        renamed = acc.renamed + if (renamed) 1 else 0,
                        errors = acc.errors + "${child.title}: moved but failed to delete source $sourceName",
                    )
                }
                else -> {
                    acc.copy(
                        moved = acc.moved + 1,
                        renamed = acc.renamed + if (renamed) 1 else 0,
                    )
                }
            }
        }

        val cleanedReport = if (cleanupEmptyChildDir(childDir)) {
            movedReport
        } else {
            movedReport.copy(
                skippedEntries = movedReport.skippedEntries + "${child.title}: failed to delete empty source folder",
            )
        }

        return if (hasBlockingIssue) {
            cleanedReport
        } else {
            cleanedReport.copy(
                fullyMergedChildIds = cleanedReport.fullyMergedChildIds + child.id,
            )
        }
    }

    private fun cleanupEmptyChildDir(childDir: UniFile): Boolean {
        val remainingEntries = childDir.listFiles()
            .orEmpty()
            .filterNot { entry -> entry.name?.endsWith(Downloader.TMP_DIR_SUFFIX) == true }
        if (remainingEntries.isNotEmpty()) {
            return true
        }
        return childDir.delete()
    }

    private fun copyEntry(source: UniFile, destinationDir: UniFile, destinationName: String): Boolean {
        return if (source.isDirectory) {
            val createdDir = destinationDir.createDirectory(destinationName) ?: return false
            source.listFiles().orEmpty().all { nested ->
                val nestedName = nested.name ?: return@all false
                copyEntry(nested, createdDir, nestedName)
            }
        } else {
            val destinationFile = destinationDir.createFile(destinationName) ?: return false
            try {
                source.openInputStream().use { input ->
                    destinationFile.openOutputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                true
            } catch (_: Throwable) {
                destinationFile.delete()
                false
            }
        }
    }

    private fun getTargetName(parentDir: UniFile, sourceName: String, childTitle: String): String {
        if (parentDir.findFile(sourceName) == null) {
            return sourceName
        }

        val dotIndex = sourceName.lastIndexOf('.')
        val hasExtension = dotIndex > 0 && dotIndex < sourceName.lastIndex
        val baseName = if (hasExtension) sourceName.substring(0, dotIndex) else sourceName
        val extension = if (hasExtension) sourceName.substring(dotIndex) else ""
        val titleSuffix = " (${childTitle.trim()})"

        val firstCandidate = "$baseName$titleSuffix$extension"
        if (parentDir.findFile(firstCandidate) == null) {
            return firstCandidate
        }

        return generateSequence(2) { it + 1 }
            .map { index -> "$baseName$titleSuffix ($index)$extension" }
            .first { candidate -> parentDir.findFile(candidate) == null }
    }

    data class MoveReport(
        val moved: Int = 0,
        val renamed: Int = 0,
        val skipped: Int = 0,
        val skippedEntries: List<String> = emptyList(),
        val errors: List<String> = emptyList(),
        val fullyMergedChildIds: Set<Long> = emptySet(),
    )

    sealed interface MoveResult {
        data class Success(val report: MoveReport) : MoveResult
        data class Partial(val report: MoveReport) : MoveResult
        data class Failure(val message: String) : MoveResult
    }
}
