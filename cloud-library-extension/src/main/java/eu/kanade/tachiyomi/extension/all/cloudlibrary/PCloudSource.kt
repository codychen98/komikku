package eu.kanade.tachiyomi.extension.all.cloudlibrary

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.all.cloudlibrary.cloudstorage.CloudLibraryIds
import eu.kanade.tachiyomi.extension.all.cloudlibrary.cloudstorage.CloudStorageClient
import eu.kanade.tachiyomi.extension.all.cloudlibrary.cloudstorage.PCloudClient

class PCloudSource : CloudLibrarySource() {

    override val name = "Cloud Library (pCloud)"
    override val id = CloudLibraryIds.PCLOUD

    override fun createClient(): CloudStorageClient {
        val prefs = preferences()
        val host = prefs.getString(PREF_HOST, PCloudClient.DEFAULT_HOST)!!.trim()
            .ifEmpty { PCloudClient.DEFAULT_HOST }
        return PCloudClient(
            host = host,
            token = prefs.getString(PREF_TOKEN, "")!!.trim(),
        )
    }

    override suspend fun pageImageUrl(path: String): String {
        // Links expire; resolve fresh per read. Do not cache across reads.
        val client = createClient() as PCloudClient
        return client.resolveFileLink(path)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_HOST
            title = "pCloud API host"
            summary = "https://api.pcloud.com or https://eapi.pcloud.com (EU)"
            setDefaultValue(PCloudClient.DEFAULT_HOST)
            dialogTitle = title
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_TOKEN
            title = "Access token"
            setDefaultValue("")
            dialogTitle = title
        }.also(screen::addPreference)

        addFolderPreference(screen)
    }

    companion object {
        private const val PREF_HOST = "pcloud_host"
        private const val PREF_TOKEN = "pcloud_token"
    }
}
