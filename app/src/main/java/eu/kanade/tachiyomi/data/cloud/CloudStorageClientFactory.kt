package eu.kanade.tachiyomi.data.cloud

import mihon.core.cloudstorage.CloudStorageClient
import mihon.core.cloudstorage.CloudStorageException
import mihon.core.cloudstorage.PCloudClient
import mihon.core.cloudstorage.WebDavClient
import tachiyomi.domain.storage.service.CloudStorageMode
import tachiyomi.domain.storage.service.CloudStoragePreferences

// CLOUD -->
object CloudStorageClientFactory {
    fun create(preferences: CloudStoragePreferences): CloudStorageClient {
        return when (preferences.mode().get()) {
            CloudStorageMode.OFF -> throw CloudStorageException("Cloud storage is disabled")
            CloudStorageMode.WEBDAV -> {
                val url = preferences.webdavUrl().get().trim()
                if (url.isEmpty() || !url.startsWith("http")) {
                    throw CloudStorageException("Invalid WebDAV URL")
                }
                val user = preferences.webdavUsername().get().trim()
                val pass = preferences.webdavPassword().get()
                if (user.isEmpty() || pass.isEmpty()) {
                    throw CloudStorageException("WebDAV username/password required")
                }
                WebDavClient(
                    baseUrl = url,
                    username = user,
                    password = pass,
                    folder = preferences.webdavFolder().get().trim(),
                )
            }
            CloudStorageMode.PCLOUD -> {
                val token = preferences.pcloudToken().get().trim()
                if (token.isEmpty()) {
                    throw CloudStorageException("pCloud access token required")
                }
                val host = preferences.pcloudHost().get().trim().ifEmpty { PCloudClient.DEFAULT_HOST }
                PCloudClient(host = host, token = token)
            }
        }
    }

    fun sourceIdForMode(mode: CloudStorageMode): Long? = when (mode) {
        CloudStorageMode.OFF -> null
        CloudStorageMode.WEBDAV -> mihon.core.cloudstorage.CloudLibraryIds.WEBDAV
        CloudStorageMode.PCLOUD -> mihon.core.cloudstorage.CloudLibraryIds.PCLOUD
    }
}
// CLOUD <--
