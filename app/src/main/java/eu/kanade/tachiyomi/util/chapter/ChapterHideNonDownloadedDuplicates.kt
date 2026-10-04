package eu.kanade.tachiyomi.util.chapter

/**
 * Drops items whose chapter number also exists as a downloaded item, keeping only the
 * downloaded version(s) of that number. Items without a recognised number (`< 0`) and
 * numbers with no downloaded version are left untouched.
 *
 * Display-only rule for the chapter list (per-manga flag
 * `Manga.CHAPTER_HIDE_NON_DOWNLOADED_DUPES`); it does not affect reader navigation or
 * batch downloads.
 */
fun <T> List<T>.hideNonDownloadedDuplicates(
    chapterNumber: (T) -> Double,
    isDownloaded: (T) -> Boolean,
): List<T> {
    val downloadedNumbers = asSequence()
        .filter(isDownloaded)
        .map(chapterNumber)
        .filter { it >= 0 }
        .toSet()
    if (downloadedNumbers.isEmpty()) return this

    return filter { item ->
        isDownloaded(item) || chapterNumber(item) !in downloadedNumbers
    }
}
