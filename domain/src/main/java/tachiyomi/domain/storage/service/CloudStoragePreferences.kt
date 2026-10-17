package tachiyomi.domain.storage.service

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class CloudStoragePreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun mode() = preferenceStore.getEnum("cloud_storage_mode", CloudStorageMode.OFF)

    fun webdavUrl() = preferenceStore.getString("cloud_webdav_url", "")
    fun webdavUsername() = preferenceStore.getString("cloud_webdav_username", "")
    fun webdavPassword() = preferenceStore.getString("cloud_webdav_password", "")
    fun webdavFolder() = preferenceStore.getString("cloud_webdav_folder", "")

    fun pcloudHost() = preferenceStore.getString("cloud_pcloud_host", "https://api.pcloud.com")
    fun pcloudToken() = preferenceStore.getString("cloud_pcloud_token", "")

    fun deleteLocalAfterUpload() = preferenceStore.getBoolean("cloud_delete_local_after_upload", true)
    fun autoMigrate() = preferenceStore.getBoolean("cloud_auto_migrate", true)
}
