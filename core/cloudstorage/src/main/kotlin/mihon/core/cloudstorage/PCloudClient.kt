package mihon.core.cloudstorage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class PCloudClient(
    host: String = DEFAULT_HOST,
    private val token: String,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CloudStorageClient {

    private val apiHost = host.trimEnd('/')

    override fun authHeaders(): Map<String, String> = emptyMap()

    override fun fileUrl(path: String): String {
        // Temporary links come from getfilelink; this returns the API path form for debug.
        val normalized = CloudPathUtil.normalize(path)
        return if (normalized.isEmpty()) "$apiHost/" else "$apiHost/$normalized"
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val response = apiGet("listfolder", "path" to pcloudPath(path))
        val metadata = response["metadata"]?.jsonObject
            ?: throw CloudStorageException("listfolder: missing metadata")
        val contents = metadata["contents"]?.jsonArray ?: JsonArray(emptyList())
        contents.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val isDir = obj["isfolder"]?.jsonPrimitive?.booleanOrNull == true
            val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
            val mtime = (obj["modified"]?.jsonPrimitive?.contentOrNull ?: "")
                .let { parsePCloudTime(it) }
            RemoteEntry(name = name, isDir = isDir, size = size, mtime = mtime)
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = CloudPathUtil.normalize(path)
        if (normalized.isEmpty()) return@withContext true
        val parent = CloudPathUtil.parent(normalized)
        val name = CloudPathUtil.name(normalized)
        list(parent).any { it.name == name }
    }

    override suspend fun mkdirs(path: String) = withContext(Dispatchers.IO) {
        val normalized = CloudPathUtil.normalize(path)
        if (normalized.isEmpty()) return@withContext
        apiGet("createfolderifnotexists", "path" to pcloudPath(normalized))
    }

    override suspend fun uploadFile(path: String, stream: InputStream, length: Long) =
        withContext(Dispatchers.IO) {
            val normalized = CloudPathUtil.normalize(path)
            val parent = CloudPathUtil.parent(normalized)
            val filename = CloudPathUtil.name(normalized)
            if (parent.isNotEmpty()) mkdirs(parent)

            val fileBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = length
                override fun writeTo(sink: BufferedSink) {
                    stream.source().use { sink.writeAll(it) }
                }
            }
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("filename", filename, fileBody)
                .build()

            val url = "$apiHost/uploadfile".toHttpUrl().newBuilder()
                .addQueryParameter("access_token", token)
                .addQueryParameter("path", pcloudPath(parent.ifEmpty { "/" }))
                .addQueryParameter("filename", filename)
                .build()

            val request = Request.Builder().url(url).post(multipart).build()
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw CloudStorageException("uploadfile HTTP ${response.code}: $body")
                }
                parseResult(body)
            }
        }

    override suspend fun downloadFile(path: String): InputStream = withContext(Dispatchers.IO) {
        val link = resolveFileLink(path)
        val request = Request.Builder().url(link).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw CloudStorageException("download failed (${response.code})")
        }
        ByteArrayInputStream(response.body.bytes()).also { response.close() }
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val normalized = CloudPathUtil.normalize(path)
        val entries = list(CloudPathUtil.parent(normalized))
        val entry = entries.find { it.name == CloudPathUtil.name(normalized) }
            ?: return@withContext
        val method = if (entry.isDir) "deletefolder" else "deletefile"
        apiGet(method, "path" to pcloudPath(normalized))
    }

    override suspend fun move(from: String, to: String) = withContext(Dispatchers.IO) {
        val fromNorm = CloudPathUtil.normalize(from)
        val toNorm = CloudPathUtil.normalize(to)
        val toParent = CloudPathUtil.parent(toNorm)
        val toName = CloudPathUtil.name(toNorm)
        if (toParent.isNotEmpty()) mkdirs(toParent)

        val entries = list(CloudPathUtil.parent(fromNorm))
        val entry = entries.find { it.name == CloudPathUtil.name(fromNorm) }
            ?: throw CloudStorageException("move: source not found: $from")
        val method = if (entry.isDir) "renamefolder" else "renamefile"
        apiGet(
            method,
            "path" to pcloudPath(fromNorm),
            "topath" to pcloudPath(toParent.ifEmpty { "/" }),
            "toname" to toName,
        )
    }

    suspend fun resolveFileLink(path: String): String {
        val response = apiGet("getfilelink", "path" to pcloudPath(path))
        val hosts = response["hosts"]?.jsonArray
            ?: throw CloudStorageException("getfilelink: missing hosts")
        val host = hosts.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?: throw CloudStorageException("getfilelink: empty hosts")
        val pathPart = response["path"]?.jsonPrimitive?.contentOrNull
            ?: throw CloudStorageException("getfilelink: missing path")
        return "https://$host$pathPart"
    }

    private fun pcloudPath(path: String): String {
        val normalized = CloudPathUtil.normalize(path)
        return if (normalized.isEmpty()) "/" else "/$normalized"
    }

    private fun apiGet(method: String, vararg params: Pair<String, String>): JsonObject {
        val urlBuilder = "$apiHost/$method".toHttpUrl().newBuilder()
            .addQueryParameter("access_token", token)
        params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val request = Request.Builder().url(urlBuilder.build()).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw CloudStorageException("$method HTTP ${response.code}: $body")
            }
            return parseResult(body)
        }
    }

    private fun parseResult(body: String): JsonObject {
        val obj = json.parseToJsonElement(body).jsonObject
        val result = obj["result"]?.jsonPrimitive?.longOrNull ?: -1L
        if (result != 0L) {
            val error = obj["error"]?.jsonPrimitive?.contentOrNull ?: "unknown error"
            throw CloudStorageException("pCloud error $result: $error")
        }
        return obj
    }

    private fun parsePCloudTime(value: String): Long {
        // pCloud returns e.g. "Mon, 16 Oct 2023 12:00:00 +0000"
        return try {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(value)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    companion object {
        const val DEFAULT_HOST = "https://api.pcloud.com"
        const val EU_HOST = "https://eapi.pcloud.com"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
    }
}
