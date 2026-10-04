package eu.kanade.tachiyomi.data.download

/**
 * Pure helpers for registry paths stored in `chapter_downloads.relative_path`
 * (`"<source dir>/<manga dir>/<chapter entry>"`).
 *
 * Kept free of Android dependencies so the matching rules used by [DownloadCache]
 * can be unit tested.
 */
object DownloadRegistryPath {

    private const val CBZ_SUFFIX = ".cbz"

    data class Segments(
        val sourceDirName: String,
        val mangaDirName: String,
        val entryName: String,
    )

    /** Splits a registry path into its three segments, or null when malformed. */
    fun parse(relativePath: String): Segments? {
        val segments = relativePath.split('/', limit = 3)
        if (segments.size < 3 || segments.any { it.isBlank() }) return null
        return Segments(
            sourceDirName = segments[0],
            mangaDirName = segments[1],
            entryName = segments[2],
        )
    }

    /**
     * Returns true when the registry entry exists in the cached directory listing.
     *
     * [chapterDirsFor] resolves an on-disk manga directory name to the set of cached chapter
     * entry names for that manga, or null when the manga directory is not cached. The cache
     * stores `.cbz` archives by basename, so both the raw entry name and the
     * extension-stripped name are accepted.
     */
    fun isCached(
        relativePath: String,
        chapterDirsFor: (mangaDirName: String) -> Set<String>?,
    ): Boolean {
        val segments = parse(relativePath) ?: return false
        val chapterDirs = chapterDirsFor(segments.mangaDirName) ?: return false

        if (segments.entryName in chapterDirs) return true
        if (!segments.entryName.endsWith(CBZ_SUFFIX)) return false
        return segments.entryName.removeSuffix(CBZ_SUFFIX) in chapterDirs
    }
}
