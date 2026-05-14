package eu.kanade.domain.chapter.interactor

import android.app.Application
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.archive.archiveReader
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Reads [ComicInfo] from a chapter download directory or `.cbz` archive under the manga download root.
 */
class ChapterDownloadComicInfoReader(
    private val application: Application,
    private val xml: XML,
) {
    suspend fun readComicInfo(file: UniFile): ComicInfo? = withContext(Dispatchers.IO) {
        runCatching {
            if (file.isDirectory) {
                file.findFile(COMIC_INFO_FILE)?.openInputStream()?.use { decodeComicInfo(it) }
            } else {
                file.archiveReader(application).use { reader ->
                    reader.getInputStream(COMIC_INFO_FILE)?.use { decodeComicInfo(it) }
                }
            }
        }.getOrNull()
    }

    private fun decodeComicInfo(stream: InputStream): ComicInfo {
        val text = stream.bufferedReader(StandardCharsets.UTF_8).readText()
        return xml.decodeFromString(ComicInfo.serializer(), text)
    }
}
