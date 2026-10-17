package eu.kanade.tachiyomi.extension.all.cloudlibrary

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.all.cloudlibrary.cloudstorage.CloudLibraryIds
import eu.kanade.tachiyomi.extension.all.cloudlibrary.cloudstorage.CloudStorageClient
import eu.kanade.tachiyomi.extension.all.cloudlibrary.cloudstorage.WebDavClient

class WebDavSource : CloudLibrarySource() {

    override val name = "Cloud Library (WebDAV)"
    override val id = CloudLibraryIds.WEBDAV

    // Folder is applied inside WebDavClient; library root is always komikku-library under that folder.
    override fun libraryRoot(): String = CloudLibraryIds.LIBRARY_ROOT

    override fun createClient(): CloudStorageClient {
        val prefs = preferences()
        return WebDavClient(
            baseUrl = prefs.getString(PREF_URL, "")!!.trim(),
            username = prefs.getString(PREF_USER, "")!!.trim(),
            password = prefs.getString(PREF_PASS, "")!!,
            folder = prefs.getString(PREF_FOLDER, "")!!.trim(),
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_URL
            title = "WebDAV URL"
            summary = "Server address (e.g. https://dav.example.com/remote.php/dav/files/user)"
            setDefaultValue("")
            dialogTitle = title
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_USER
            title = "Username"
            setDefaultValue("")
            dialogTitle = title
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASS
            title = "Password"
            setDefaultValue("")
            dialogTitle = title
        }.also(screen::addPreference)

        addFolderPreference(screen)
    }

    companion object {
        private const val PREF_URL = "webdav_url"
        private const val PREF_USER = "webdav_user"
        private const val PREF_PASS = "webdav_pass"
    }
}
