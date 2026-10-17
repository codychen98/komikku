package eu.kanade.tachiyomi.extension.all.cloudlibrary

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.Locale

data class ParsedComicInfo(
    val series: String? = null,
    val title: String? = null,
    val number: String? = null,
    val summary: String? = null,
    val writer: String? = null,
    val penciller: String? = null,
    val genre: String? = null,
    val status: String? = null,
    val categories: String? = null,
    val source: String? = null,
    val web: String? = null,
)

object ComicInfoParser {
    fun parse(stream: InputStream): ParsedComicInfo {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(stream, null)

        var series: String? = null
        var title: String? = null
        var number: String? = null
        var summary: String? = null
        var writer: String? = null
        var penciller: String? = null
        var genre: String? = null
        var status: String? = null
        var categories: String? = null
        var source: String? = null
        var web: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase(Locale.ROOT)) {
                    "series" -> series = parser.nextText().trim().ifEmpty { null }
                    "title" -> title = parser.nextText().trim().ifEmpty { null }
                    "number" -> number = parser.nextText().trim().ifEmpty { null }
                    "summary" -> summary = parser.nextText().trim().ifEmpty { null }
                    "writer" -> writer = parser.nextText().trim().ifEmpty { null }
                    "penciller" -> penciller = parser.nextText().trim().ifEmpty { null }
                    "genre" -> genre = parser.nextText().trim().ifEmpty { null }
                    "publishingstatustachiyomi" -> status = parser.nextText().trim().ifEmpty { null }
                    "categories" -> categories = parser.nextText().trim().ifEmpty { null }
                    "sourcemihon" -> source = parser.nextText().trim().ifEmpty { null }
                    "web" -> web = parser.nextText().trim().ifEmpty { null }
                }
            }
            event = parser.next()
        }

        return ParsedComicInfo(
            series = series,
            title = title,
            number = number,
            summary = summary,
            writer = writer,
            penciller = penciller,
            genre = genre,
            status = status,
            categories = categories,
            source = source,
            web = web,
        )
    }
}
