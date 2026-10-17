package eu.kanade.tachiyomi.extension.all.cloudlibrary.cloudstorage

import java.io.InputStream

interface CloudStorageClient {
    suspend fun list(path: String): List<RemoteEntry>
    suspend fun exists(path: String): Boolean
    suspend fun mkdirs(path: String)
    suspend fun uploadFile(path: String, stream: InputStream, length: Long)
    suspend fun downloadFile(path: String): InputStream
    suspend fun delete(path: String)
    suspend fun move(from: String, to: String)
    fun fileUrl(path: String): String
    fun authHeaders(): Map<String, String>
}
