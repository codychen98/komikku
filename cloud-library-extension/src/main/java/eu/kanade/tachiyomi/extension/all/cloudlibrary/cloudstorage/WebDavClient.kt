package eu.kanade.tachiyomi.extension.all.cloudlibrary.cloudstorage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class WebDavClient(
    baseUrl: String,
    username: String,
    password: String,
    folder: String = "",
    private val client: OkHttpClient = defaultClient(),
) : CloudStorageClient {

    private val rootUrl = baseUrl.trimEnd('/')
    private val rootFolder = CloudPathUtil.normalize(folder)
    private val credentials = Credentials.basic(username.trim(), password)

    override fun authHeaders(): Map<String, String> = mapOf("Authorization" to credentials)

    override fun fileUrl(path: String): String {
        val encoded = CloudPathUtil.encodePath(absolutePath(path))
        return if (encoded.isEmpty()) rootUrl else "$rootUrl/$encoded"
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val url = fileUrl(path).ensureTrailingSlash()
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body)
            .header("Authorization", credentials)
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 207) {
                throw CloudStorageException("PROPFIND failed (${response.code}): ${response.message}")
            }
            val xml = response.body.string()
            parseMultistatus(xml, path)
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val parent = CloudPathUtil.parent(path)
        val name = CloudPathUtil.name(path)
        if (name.isEmpty()) return@withContext true
        list(parent).any { it.name == name }
    }

    override suspend fun mkdirs(path: String) = withContext(Dispatchers.IO) {
        val normalized = CloudPathUtil.normalize(path)
        if (normalized.isEmpty()) return@withContext
        var current = ""
        for (segment in normalized.split('/')) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            if (!exists(current)) {
                createCollection(current)
            }
        }
    }

    override suspend fun uploadFile(path: String, stream: InputStream, length: Long) =
        withContext(Dispatchers.IO) {
            val parent = CloudPathUtil.parent(path)
            if (parent.isNotEmpty()) mkdirs(parent)
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = length
                override fun writeTo(sink: BufferedSink) {
                    stream.source().use { sink.writeAll(it) }
                }
            }
            val request = Request.Builder()
                .url(fileUrl(path))
                .put(body)
                .header("Authorization", credentials)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw CloudStorageException("PUT failed (${response.code}): ${response.message}")
                }
            }
        }

    override suspend fun downloadFile(path: String): InputStream = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(fileUrl(path))
            .get()
            .header("Authorization", credentials)
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw CloudStorageException("GET failed (${response.code}): ${response.message}")
        }
        // Buffer so the response can be closed after returning
        ByteArrayInputStream(response.body.bytes()).also { response.close() }
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(fileUrl(path))
            .delete()
            .header("Authorization", credentials)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                throw CloudStorageException("DELETE failed (${response.code}): ${response.message}")
            }
        }
    }

    override suspend fun move(from: String, to: String) = withContext(Dispatchers.IO) {
        val parent = CloudPathUtil.parent(to)
        if (parent.isNotEmpty()) mkdirs(parent)
        val request = Request.Builder()
            .url(fileUrl(from))
            .method("MOVE", null)
            .header("Authorization", credentials)
            .header("Destination", fileUrl(to))
            .header("Overwrite", "T")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CloudStorageException("MOVE failed (${response.code}): ${response.message}")
            }
        }
    }

    private suspend fun createCollection(path: String) {
        val request = Request.Builder()
            .url(fileUrl(path))
            .method("MKCOL", null)
            .header("Authorization", credentials)
            .header("Content-Length", "0")
            .build()
        client.newCall(request).execute().use { response ->
            // 201 created, 405 already exists, 409 conflict treated as ok for idempotency
            if (!response.isSuccessful && response.code != 405 && response.code != 409) {
                throw CloudStorageException("MKCOL failed (${response.code}): ${response.message}")
            }
        }
    }

    private fun absolutePath(path: String): String {
        val relative = CloudPathUtil.normalize(path)
        return when {
            rootFolder.isEmpty() -> relative
            relative.isEmpty() -> rootFolder
            else -> "$rootFolder/$relative"
        }
    }

    private fun parseMultistatus(xml: String, requestPath: String): List<RemoteEntry> {
        val requestAbsolute = CloudPathUtil.normalize(absolutePath(requestPath))
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        val results = mutableListOf<RemoteEntry>()
        var event = parser.eventType
        var href: String? = null
        var isCollection = false
        var size = 0L
        var mtime = 0L
        var inProp = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase(Locale.ROOT)) {
                        "response" -> {
                            href = null
                            isCollection = false
                            size = 0L
                            mtime = 0L
                        }
                        "href" -> href = unescapeXml(parser.nextText())
                        "prop" -> inProp = true
                        "collection" -> if (inProp) isCollection = true
                        "getcontentlength" -> {
                            if (inProp) {
                                size = parser.nextText().trim().toLongOrNull() ?: 0L
                            }
                        }
                        "getlastmodified" -> {
                            if (inProp) {
                                mtime = parseHttpDate(parser.nextText().trim())
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase(Locale.ROOT)) {
                        "prop" -> inProp = false
                        "response" -> {
                            val rawHref = href
                            if (rawHref != null) {
                                val entryPath = hrefToPath(rawHref)
                                val entryAbsolute = CloudPathUtil.normalize(entryPath)
                                // Skip the requested directory itself
                                if (entryAbsolute != requestAbsolute && entryAbsolute.isNotEmpty()) {
                                    val name = CloudPathUtil.name(entryAbsolute)
                                    if (name.isNotEmpty()) {
                                        results.add(
                                            RemoteEntry(
                                                name = CloudPathUtil.decode(name),
                                                isDir = isCollection || (size == 0L && !name.contains('.')),
                                                size = size,
                                                mtime = mtime,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        return results.distinctBy { it.name }
    }

    private fun hrefToPath(href: String): String {
        val decoded = CloudPathUtil.decode(href)
        val withoutHost = when {
            decoded.startsWith("http://") || decoded.startsWith("https://") -> {
                val pathStart = decoded.indexOf('/', decoded.indexOf("://") + 3)
                if (pathStart >= 0) decoded.substring(pathStart) else ""
            }
            else -> decoded
        }
        val trimmed = withoutHost.trim('/')
        val rootEncoded = CloudPathUtil.encodePath(rootFolder)
        val rootPlain = rootFolder
        return when {
            rootPlain.isNotEmpty() && trimmed.startsWith(rootPlain) ->
                trimmed.removePrefix(rootPlain).trim('/')
            rootEncoded.isNotEmpty() && trimmed.startsWith(rootEncoded) ->
                CloudPathUtil.decode(trimmed.removePrefix(rootEncoded).trim('/'))
            else -> trimmed
        }
    }

    private fun unescapeXml(value: String): String {
        return value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun parseHttpDate(value: String): Long {
        if (value.isEmpty()) return 0L
        return try {
            HTTP_DATE.parse(value)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun String.ensureTrailingSlash(): String =
        if (endsWith('/')) this else "$this/"

    companion object {
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
              </d:prop>
            </d:propfind>
        """.trimIndent()

        private val HTTP_DATE = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
