# Cloud Storage Integration Plan — PCloud API + WebDAV

## Problem Statement

Users want to download manga to cloud storage and read from it across multiple devices, avoiding duplicate downloads on each device and saving physical storage. Currently, Komikku only supports local device storage for downloads.

**Goal**: Add PCloud API and WebDAV as alternative storage locations. The user picks ONE storage backend (Local, PCloud, or WebDAV) in Settings → Data & Storage → Storage location. All downloads go to the chosen backend; reading loads from that backend.

## How It Works (User Perspective)

1. Go to **Settings → Data & Storage**
2. Tap **"Storage location"** (same single row as current, subtitle shows active backend)
3. **Dialog 1** appears with 3 radio options:
   - **Local storage** (current selection highlighted) → selecting this opens the Android folder picker (identical to current behavior)
   - **PCloud** → selecting this opens a **PCloud config dialog** with fields: access token, base folder, EU region toggle, and a "Test" button
   - **WebDAV** → selecting this opens a **WebDAV config dialog** with fields: server URL, username, password, base path, and a "Test" button
4. In the config dialog, fill in credentials → tap **"Test"** to verify → tap **"Save"** to confirm
5. The subtitle updates to show the active backend (e.g., "PCloud: /Komikku" or "WebDAV: https://...")
6. All subsequent downloads go to the chosen storage backend
7. Reading a downloaded chapter streams/caches from cloud
8. Deleting a chapter removes it from cloud

## Architecture: "Temp-Local + Cloud" Model

### Why not a pure UniFile cloud subclass?

Android's `ZipWriter` (used for CBZ creation) calls `openFileDescriptor()` via `ContentResolver`, which requires a real Android URI backed by a file provider. Cloud URIs cannot produce a `ParcelFileDescriptor`. Creating a custom `ContentProvider` for cloud storage would be extremely fragile.

### The practical approach:

- **Downloads**: Pages are fetched from source → written to a **temp staging area** in app cache → assembled into CBZ → **uploaded to cloud** → temp deleted. The user never sees or manages temp files.
- **Reading**: Chapter CBZ downloaded from cloud → **cached locally** in LRU cache (~500MB) → read via existing `ArchivePageLoader`. Cache auto-evicts old entries.
- **DownloadCache**: When cloud is active, `renewCache()` fetches directory listings from cloud API instead of local filesystem scan.
- **Deletes**: Delete from cloud directly.

**The permanent storage is ONLY on the chosen backend.** Temp/cache is ephemeral.

---

## Detailed File-by-File Implementation

### FILES TO CREATE (6 new files)

All under `app/src/main/java/eu/kanade/tachiyomi/data/cloud/`

---

#### 1. `CloudStorageClient.kt` — Interface + Data Classes

```kotlin
package eu.kanade.tachiyomi.data.cloud

import java.io.InputStream

data class CloudFileMetadata(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val remoteId: String = "",  // PCloud folderid/fileid as string
)

interface CloudStorageClient {
    suspend fun testConnection(): Result<Unit>
    suspend fun listFolder(path: String): Result<List<CloudFileMetadata>>
    suspend fun createFolder(path: String): Result<CloudFileMetadata>
    suspend fun uploadFile(remotePath: String, inputStream: InputStream, length: Long): Result<CloudFileMetadata>
    suspend fun downloadFile(remotePath: String): Result<InputStream>
    suspend fun delete(remotePath: String, isDirectory: Boolean): Result<Unit>
    suspend fun rename(oldPath: String, newPath: String, isDirectory: Boolean): Result<Unit>
    suspend fun exists(path: String): Result<Boolean>
    suspend fun stat(path: String): Result<CloudFileMetadata?>
}
```

---

#### 2. `PCloudClient.kt` — PCloud REST API Implementation

```kotlin
package eu.kanade.tachiyomi.data.cloud

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType

class PCloudClient(
    private val accessToken: String,
    private val useEuRegion: Boolean,
    private val client: OkHttpClient,
) : CloudStorageClient {

    private val baseUrl: String
        get() = if (useEuRegion) "https://eapi.pcloud.com" else "https://api.pcloud.com"

    // Helper: build URL with access_token
    private fun apiUrl(method: String, params: Map<String, String> = emptyMap()): HttpUrl {
        val builder = "$baseUrl/$method".toHttpUrl().newBuilder()
            .addQueryParameter("access_token", accessToken)
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    // Helper: execute request, parse JSON, check result==0
    private suspend fun apiCall(method: String, params: Map<String, String>): Result<JsonObject> { ... }
}
```

**Key PCloud API mappings:**

| CloudStorageClient method | PCloud endpoint | Key params |
|---------------------------|-----------------|------------|
| `testConnection()` | `listfolder` | `path=/` |
| `listFolder(path)` | `listfolder` | `path=<path>` |
| `createFolder(path)` | `createfolderiifnotexists` | `path=<path>` |
| `uploadFile(path, stream)` | `uploadfile` | `path=<parentFolder>`, multipart body with file |
| `downloadFile(path)` | `getfilelink` then HTTP GET | `path=<path>` → get hosts[0]+path → GET |
| `delete(path, isDir=false)` | `deletefile` | `path=<path>` |
| `delete(path, isDir=true)` | `deletefolderrecursive` | `path=<path>` |
| `rename(old, new, isDir)` | `renamefile` / `renamefolder` | `path=<old>`, `topath=<new>` |
| `exists(path)` | `stat` | `path=<path>`, check result==0 vs 2009 |
| `stat(path)` | `stat` | `path=<path>` |

**PCloud `downloadFile` flow (two-step):**
1. Call `getfilelink?path=<path>&access_token=<token>` → returns `{ hosts: ["c63.pcloud.com"], path: "/encoded/path" }`
2. HTTP GET `https://${hosts[0]}${path}` → returns raw file bytes as InputStream

**PCloud `uploadFile` flow:**
- POST `uploadfile?path=<parentFolder>&filename=<name>&access_token=<token>`
- Body: `MultipartBody` with file part
- Note: `path` param is the FOLDER, `filename` is the file name within that folder

**PCloud `createFolder` — use `createfolderifnotexists`:**
- Endpoint: `createfolderifnotexists?path=<fullPath>&access_token=<token>`
- This is idempotent (won't error if folder already exists, unlike `createfolder` which returns 2004)

**PCloud error handling:**
- All responses have `"result": <int>` — 0 means success
- Non-zero result means error; `"error": "<message>"` contains description
- Key errors: 1000=auth required, 2005=folder not found, 2009=file not found, 2008=quota exceeded

---

#### 3. `WebDavClient.kt` — WebDAV Implementation

```kotlin
package eu.kanade.tachiyomi.data.cloud

import okhttp3.*
import okhttp3.Credentials

class WebDavClient(
    private val baseUrl: String,  // e.g., "https://webdav.example.com/dav"
    private val username: String,
    private val password: String,
    private val client: OkHttpClient,
) : CloudStorageClient {
    private val credentials = Credentials.basic(username, password)
    // ...
}
```

**Reference existing code:** The project already has `WebDavSyncService` at `app/.../data/sync/service/WebDavSyncService.kt` which shows the pattern for OkHttp + WebDAV. Reuse the same patterns:

| CloudStorageClient method | WebDAV method | Details |
|---------------------------|---------------|---------|
| `testConnection()` | `PROPFIND /` | Depth: 0, check response is 207 Multi-Status |
| `listFolder(path)` | `PROPFIND <path>` | Depth: 1, parse XML multistatus response |
| `createFolder(path)` | `MKCOL <path>` | Create each path segment; ignore 405 (exists) |
| `uploadFile(path, stream)` | `PUT <path>` | RequestBody from stream |
| `downloadFile(path)` | `GET <path>` | Return response body as InputStream |
| `delete(path)` | `DELETE <path>` | Works for both files and directories |
| `rename(old, new)` | `MOVE <old>` | `Destination: <new>` header |
| `exists(path)` | `HEAD <path>` | 200=exists, 404=not |
| `stat(path)` | `PROPFIND <path>` | Depth: 0, parse single response |

**PROPFIND XML parsing** — Response body for `listFolder` is XML multistatus:
```xml
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/path/to/item</D:href>
    <D:propstat>
      <D:prop>
        <D:displayname>item_name</D:displayname>
        <D:resourcetype><D:collection/></D:resourcetype>  <!-- present = directory -->
        <D:getcontentlength>1234</D:getcontentlength>
        <D:getlastmodified>Mon, 01 Jan 2024 00:00:00 GMT</D:getlastmodified>
      </D:prop>
    </D:propstat>
  </D:response>
</D:multistatus>
```

Parse with `XmlPullParser` (Android built-in). Check for `<D:collection/>` inside `<D:resourcetype>` to determine if entry is a directory.

**OkHttp client config** (same as existing WebDavSyncService):
```kotlin
OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)  // longer for file downloads
    .writeTimeout(60, TimeUnit.SECONDS)  // longer for file uploads
    .build()
```

---

#### 4. `CloudStorageClientFactory.kt` — Factory

```kotlin
package eu.kanade.tachiyomi.data.cloud

import eu.kanade.tachiyomi.network.NetworkHelper
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.storage.service.CloudStorageType

class CloudStorageClientFactory(
    private val storagePreferences: StoragePreferences,
    private val networkHelper: NetworkHelper,
) {
    fun create(): CloudStorageClient? {
        return when (CloudStorageType.fromValue(storagePreferences.cloudStorageType().get())) {
            CloudStorageType.LOCAL -> null
            CloudStorageType.PCLOUD -> PCloudClient(
                accessToken = storagePreferences.pcloudAccessToken().get(),
                useEuRegion = storagePreferences.pcloudUseEuRegion().get(),
                client = networkHelper.client,
            )
            CloudStorageType.WEBDAV -> WebDavClient(
                baseUrl = storagePreferences.webdavUrl().get().trimEnd('/'),
                username = storagePreferences.webdavUsername().get(),
                password = storagePreferences.webdavPassword().get(),
                client = networkHelper.client,
            )
        }
    }
}
```

---

#### 5. `CloudUploadManager.kt` — Post-Download Upload

This is called by `Downloader` after a chapter finishes downloading to the local temp area.

```kotlin
package eu.kanade.tachiyomi.data.cloud

import kotlinx.coroutines.*

class CloudUploadManager(
    private val clientFactory: CloudStorageClientFactory,
    private val storagePreferences: StoragePreferences,
) {
    /**
     * Upload a completed chapter to cloud storage.
     * Called from Downloader after CBZ/directory is finalized in the temp staging area.
     *
     * @param stagingDir UniFile pointing to the temp staging directory (app cache)
     * @param chapterFilename e.g., "Chapter 1_abc123.cbz" or "Chapter 1_abc123" (directory)
     * @param sourceDirName e.g., "MangaDex (en)"
     * @param mangaDirName e.g., "One Piece"
     */
    suspend fun uploadChapter(
        stagingDir: UniFile,
        chapterFilename: String,
        sourceDirName: String,
        mangaDirName: String,
    ) {
        val client = clientFactory.create() ?: return
        val basePath = storagePreferences.cloudBaseFolderPath().get().trimEnd('/')
        val remoteMangaPath = "$basePath/downloads/$sourceDirName/$mangaDirName"

        // Ensure remote directory structure exists
        client.createFolder("$basePath/downloads")
        client.createFolder("$basePath/downloads/$sourceDirName")
        client.createFolder(remoteMangaPath)

        val chapterFile = stagingDir.findFile(chapterFilename)
        if (chapterFile?.isFile == true) {
            // Upload CBZ file
            chapterFile.openInputStream()?.use { stream ->
                client.uploadFile(
                    "$remoteMangaPath/$chapterFilename",
                    stream,
                    chapterFile.length(),
                )
            }
        } else if (chapterFile?.isDirectory == true) {
            // Upload directory of images
            val remoteChapterPath = "$remoteMangaPath/$chapterFilename"
            client.createFolder(remoteChapterPath)
            chapterFile.listFiles()?.forEach { file ->
                file.openInputStream()?.use { stream ->
                    client.uploadFile(
                        "$remoteChapterPath/${file.name}",
                        stream,
                        file.length(),
                    )
                }
            }
        }
    }
}
```

---

#### 6. `CloudChapterCache.kt` — On-Demand Download Cache for Reading

```kotlin
package eu.kanade.tachiyomi.data.cloud

import android.content.Context
import com.hippo.unifile.UniFile
import java.io.File

class CloudChapterCache(
    private val context: Context,
    private val clientFactory: CloudStorageClientFactory,
    private val storagePreferences: StoragePreferences,
) {
    private val cacheDir = File(context.cacheDir, "cloud_chapters")
    private val maxCacheSize: Long = 500L * 1024 * 1024  // 500MB

    /**
     * Ensures a chapter is available locally for reading.
     * Downloads from cloud if not already cached.
     * Returns a local UniFile pointing to the cached chapter.
     */
    suspend fun ensureChapterAvailable(
        sourceDirName: String,
        mangaDirName: String,
        chapterDirName: String,
    ): UniFile? {
        // Check local cache first
        val localDir = File(cacheDir, "$sourceDirName/$mangaDirName")
        val localCbz = File(localDir, "$chapterDirName.cbz")
        if (localCbz.exists()) return UniFile.fromFile(localCbz)

        val localChapterDir = File(localDir, chapterDirName)
        if (localChapterDir.exists() && localChapterDir.listFiles()?.isNotEmpty() == true) {
            return UniFile.fromFile(localChapterDir)
        }

        // Download from cloud
        val client = clientFactory.create() ?: return null
        val basePath = storagePreferences.cloudBaseFolderPath().get().trimEnd('/')
        val remoteMangaPath = "$basePath/downloads/$sourceDirName/$mangaDirName"

        // Try CBZ first (preferred format for cloud)
        val cbzResult = client.downloadFile("$remoteMangaPath/$chapterDirName.cbz")
        if (cbzResult.isSuccess) {
            localDir.mkdirs()
            cbzResult.getOrNull()?.use { input ->
                localCbz.outputStream().use { output -> input.copyTo(output) }
            }
            evictIfNeeded()
            return UniFile.fromFile(localCbz)
        }

        // Fallback: try directory of images
        val listing = client.listFolder("$remoteMangaPath/$chapterDirName")
        if (listing.isSuccess) {
            val files = listing.getOrNull()?.filter { !it.isDirectory } ?: return null
            if (files.isEmpty()) return null
            localChapterDir.mkdirs()
            files.forEach { file ->
                client.downloadFile(file.path).getOrNull()?.use { input ->
                    File(localChapterDir, file.name).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            evictIfNeeded()
            return UniFile.fromFile(localChapterDir)
        }

        return null
    }

    /** Remove cached chapter */
    fun removeChapter(sourceDirName: String, mangaDirName: String, chapterDirName: String) {
        val localDir = File(cacheDir, "$sourceDirName/$mangaDirName")
        File(localDir, "$chapterDirName.cbz").delete()
        File(localDir, chapterDirName).deleteRecursively()
    }

    /** LRU eviction: delete oldest files when cache exceeds maxCacheSize */
    private fun evictIfNeeded() {
        val allFiles = cacheDir.walkBottomUp().filter { it.isFile }.toList()
        val totalSize = allFiles.sumOf { it.length() }
        if (totalSize <= maxCacheSize) return

        val sorted = allFiles.sortedBy { it.lastModified() }
        var freed = 0L
        val target = totalSize - maxCacheSize
        for (file in sorted) {
            freed += file.length()
            file.delete()
            if (freed >= target) break
        }
        // Clean up empty directories
        cacheDir.walkBottomUp().filter { it.isDirectory && it.listFiles()?.isEmpty() == true }.forEach { it.delete() }
    }

    fun clearCache() {
        cacheDir.deleteRecursively()
    }
}
```

---

### FILES TO MODIFY (8 files)

---

#### 1. `StoragePreferences.kt`

**File**: `domain/src/main/java/tachiyomi/domain/storage/service/StoragePreferences.kt`

**Add** `CloudStorageType` enum and cloud-related preferences:

```kotlin
package tachiyomi.domain.storage.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.storage.FolderProvider

// KMK -->
enum class CloudStorageType(val value: Int) {
    LOCAL(0),
    PCLOUD(1),
    WEBDAV(2);
    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: LOCAL
    }
}
// KMK <--

class StoragePreferences(
    private val folderProvider: FolderProvider,
    private val preferenceStore: PreferenceStore,
) {
    fun baseStorageDirectory() = preferenceStore.getString(
        Preference.appStateKey("storage_dir"),
        folderProvider.path(),
    )

    // KMK -->
    fun cloudStorageType() = preferenceStore.getInt("cloud_storage_type", CloudStorageType.LOCAL.value)

    // PCloud settings
    fun pcloudAccessToken() = preferenceStore.getString("pcloud_access_token", "")
    fun pcloudBaseFolderPath() = preferenceStore.getString("pcloud_base_folder", "/Komikku")
    fun pcloudUseEuRegion() = preferenceStore.getBoolean("pcloud_use_eu_region", false)

    // WebDAV settings
    fun webdavUrl() = preferenceStore.getString("cloud_webdav_url", "")
    fun webdavUsername() = preferenceStore.getString("cloud_webdav_username", "")
    fun webdavPassword() = preferenceStore.getString("cloud_webdav_password", "")
    fun webdavBasePath() = preferenceStore.getString("cloud_webdav_base_path", "/Komikku")
    // KMK <--
}
```

---

#### 2. `SettingsDataScreen.kt`

**File**: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsDataScreen.kt`

**UI Flow:** The "Storage location" preference stays as a SINGLE item (not a group).
Tapping it opens a **selection dialog** with 3 radio options. After selecting, either
the folder picker opens (Local) or a config dialog opens (PCloud/WebDAV).

The subtitle dynamically shows the active backend:
- Local: `/sdcard/Komikku` (same as current)
- PCloud: `PCloud: /Komikku`
- WebDAV: `WebDAV: https://example.com/dav`

**Visual flow:**

```
┌─────────────────────────────────┐
│  Storage location               │  ← Tap this
│  PCloud: /Komikku               │  ← Dynamic subtitle
└─────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  Choose storage type            │  ← Dialog 1: type selection
│                                 │
│  ◉ Local storage                │
│  ○ PCloud                       │
│  ○ WebDAV                       │
│                                 │
│              [Cancel]           │
└─────────────────────────────────┘
         │
         ├── Local selected → opens Android folder picker (existing behavior)
         │
         ├── PCloud selected → Dialog 2:
         │   ┌─────────────────────────────────┐
         │   │  PCloud Configuration            │
         │   │                                  │
         │   │  Access Token: [______________]  │
         │   │  Base Folder:  [/Komikku_______] │
         │   │  ☐ Use EU region                 │
         │   │                                  │
         │   │  [Test]    [Cancel]    [Save]     │
         │   └─────────────────────────────────┘
         │
         └── WebDAV selected → Dialog 2:
             ┌─────────────────────────────────┐
             │  WebDAV Configuration            │
             │                                  │
             │  Server URL: [________________]  │
             │  Username:   [________________]  │
             │  Password:   [________________]  │
             │  Base Path:  [/Komikku________]  │
             │                                  │
             │  [Test]    [Cancel]    [Save]     │
             └─────────────────────────────────┘
```

**Implementation:**

Keep `getStorageLocationPref()` as a `TextPreference` (single row, not a group),
but replace its `onClick` to show the type selection dialog. Use dialog state
variables to manage the multi-step flow.

```kotlin
// KMK -->
@Composable
private fun getStorageLocationPref(
    storagePreferences: StoragePreferences,
): Preference.PreferenceItem.TextPreference {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickStorageLocation = storageLocationPicker(storagePreferences.baseStorageDirectory())

    // Dialog state
    var showTypeDialog by remember { mutableStateOf(false) }
    var showPCloudDialog by remember { mutableStateOf(false) }
    var showWebDavDialog by remember { mutableStateOf(false) }

    // Current state for subtitle
    val cloudType by storagePreferences.cloudStorageType().collectAsState()
    val currentType = CloudStorageType.fromValue(cloudType)

    // --- Dialog 1: Storage type selection ---
    if (showTypeDialog) {
        AlertDialog(
            onDismissRequest = { showTypeDialog = false },
            title = { Text(text = stringResource(KMR.strings.pref_storage_type)) },
            text = {
                Column {
                    // Radio option: Local storage
                    DialogRow(
                        label = stringResource(KMR.strings.cloud_storage_local),
                        isSelected = currentType == CloudStorageType.LOCAL,
                        onSelected = {
                            storagePreferences.cloudStorageType().set(CloudStorageType.LOCAL.value)
                            showTypeDialog = false
                            // Open folder picker for local
                            try {
                                val storagePref = storagePreferences.baseStorageDirectory()
                                allowAccessStorage(context, storagePref) {
                                    pickStorageLocation.launch(null)
                                }
                            } catch (_: Exception) {
                                context.toast(MR.strings.file_picker_error)
                            }
                        },
                    )
                    // Radio option: PCloud
                    DialogRow(
                        label = stringResource(KMR.strings.cloud_storage_pcloud),
                        isSelected = currentType == CloudStorageType.PCLOUD,
                        onSelected = {
                            showTypeDialog = false
                            showPCloudDialog = true
                        },
                    )
                    // Radio option: WebDAV
                    DialogRow(
                        label = stringResource(KMR.strings.cloud_storage_webdav),
                        isSelected = currentType == CloudStorageType.WEBDAV,
                        onSelected = {
                            showTypeDialog = false
                            showWebDavDialog = true
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showTypeDialog = false }) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    // --- Dialog 2a: PCloud configuration ---
    if (showPCloudDialog) {
        var token by rememberSaveable { mutableStateOf(storagePreferences.pcloudAccessToken().get()) }
        var baseFolder by rememberSaveable { mutableStateOf(storagePreferences.pcloudBaseFolderPath().get()) }
        var useEu by rememberSaveable { mutableStateOf(storagePreferences.pcloudUseEuRegion().get()) }
        var testResult by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showPCloudDialog = false },
            title = { Text(text = stringResource(KMR.strings.cloud_storage_pcloud)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text(stringResource(KMR.strings.pref_pcloud_access_token)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = baseFolder,
                        onValueChange = { baseFolder = it },
                        label = { Text(stringResource(KMR.strings.pref_pcloud_base_folder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useEu, onCheckedChange = { useEu = it })
                        Text(stringResource(KMR.strings.pref_pcloud_eu_region))
                    }
                    // Test Connection button
                    TextButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val client = PCloudClient(
                                    accessToken = token,
                                    useEuRegion = useEu,
                                    client = Injekt.get<NetworkHelper>().client,
                                )
                                val result = client.testConnection()
                                withUIContext {
                                    testResult = if (result.isSuccess) {
                                        context.stringResource(KMR.strings.cloud_test_connection_success)
                                    } else {
                                        context.stringResource(
                                            KMR.strings.cloud_test_connection_fail,
                                            result.exceptionOrNull()?.message ?: "Unknown error",
                                        )
                                    }
                                    context.toast(testResult!!)
                                }
                            }
                        },
                    ) {
                        Text(stringResource(KMR.strings.cloud_test_connection))
                    }
                    testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = token.isNotBlank(),
                    onClick = {
                        storagePreferences.cloudStorageType().set(CloudStorageType.PCLOUD.value)
                        storagePreferences.pcloudAccessToken().set(token)
                        storagePreferences.pcloudBaseFolderPath().set(baseFolder)
                        storagePreferences.pcloudUseEuRegion().set(useEu)
                        showPCloudDialog = false
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPCloudDialog = false }) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    // --- Dialog 2b: WebDAV configuration ---
    if (showWebDavDialog) {
        var serverUrl by rememberSaveable { mutableStateOf(storagePreferences.webdavUrl().get()) }
        var username by rememberSaveable { mutableStateOf(storagePreferences.webdavUsername().get()) }
        var password by rememberSaveable { mutableStateOf(storagePreferences.webdavPassword().get()) }
        var basePath by rememberSaveable { mutableStateOf(storagePreferences.webdavBasePath().get()) }
        var testResult by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showWebDavDialog = false },
            title = { Text(text = stringResource(KMR.strings.cloud_storage_webdav)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text(stringResource(KMR.strings.pref_cloud_webdav_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(KMR.strings.pref_cloud_webdav_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(KMR.strings.pref_cloud_webdav_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = basePath,
                        onValueChange = { basePath = it },
                        label = { Text(stringResource(KMR.strings.pref_cloud_webdav_base_path)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Test Connection button
                    TextButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val client = WebDavClient(
                                    baseUrl = serverUrl.trimEnd('/'),
                                    username = username,
                                    password = password,
                                    client = Injekt.get<NetworkHelper>().client,
                                )
                                val result = client.testConnection()
                                withUIContext {
                                    testResult = if (result.isSuccess) {
                                        context.stringResource(KMR.strings.cloud_test_connection_success)
                                    } else {
                                        context.stringResource(
                                            KMR.strings.cloud_test_connection_fail,
                                            result.exceptionOrNull()?.message ?: "Unknown error",
                                        )
                                    }
                                    context.toast(testResult!!)
                                }
                            }
                        },
                    ) {
                        Text(stringResource(KMR.strings.cloud_test_connection))
                    }
                    testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = serverUrl.isNotBlank(),
                    onClick = {
                        storagePreferences.cloudStorageType().set(CloudStorageType.WEBDAV.value)
                        storagePreferences.webdavUrl().set(serverUrl)
                        storagePreferences.webdavUsername().set(username)
                        storagePreferences.webdavPassword().set(password)
                        storagePreferences.webdavBasePath().set(basePath)
                        showWebDavDialog = false
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWebDavDialog = false }) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    // --- Build dynamic subtitle ---
    val subtitle = when (currentType) {
        CloudStorageType.LOCAL -> storageLocationText(storagePreferences.baseStorageDirectory())
        CloudStorageType.PCLOUD -> "PCloud: ${storagePreferences.pcloudBaseFolderPath().get()}"
        CloudStorageType.WEBDAV -> "WebDAV: ${storagePreferences.webdavUrl().get()}"
    }

    return Preference.PreferenceItem.TextPreference(
        title = stringResource(MR.strings.pref_storage_location),
        subtitle = subtitle,
        onClick = { showTypeDialog = true },
    )
}
// KMK <--
```

**DialogRow helper** — reuse the pattern from `ListPreferenceWidget.kt`:
```kotlin
@Composable
private fun DialogRow(
    label: String,
    isSelected: Boolean,
    onSelected: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .selectable(
                selected = isSelected,
                onClick = { onSelected() },
            )
            .fillMaxWidth()
            .minimumInteractiveComponentSize(),
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.merge(),
            modifier = Modifier.padding(start = 24.dp),
        )
    }
}
```

**In `getPreferences()`** (line ~131): NO CHANGE NEEDED. `getStorageLocationPref()`
still returns a single `Preference.PreferenceItem.TextPreference`, same as before.
The dialogs are managed by composable state inside the function.

**Additional imports needed:**
```kotlin
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import eu.kanade.tachiyomi.data.cloud.PCloudClient
import eu.kanade.tachiyomi.data.cloud.WebDavClient
import tachiyomi.domain.storage.service.CloudStorageType
```

---

#### 3. `Downloader.kt` — Hook cloud upload after download

**File**: `app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt`

**Changes:**

**a) Add imports and dependencies:**
```kotlin
import eu.kanade.tachiyomi.data.cloud.CloudUploadManager
import tachiyomi.domain.storage.service.CloudStorageType
import tachiyomi.domain.storage.service.StoragePreferences
```

In constructor, add:
```kotlin
// KMK -->
private val storagePreferences: StoragePreferences = Injekt.get(),
private val cloudUploadManager: CloudUploadManager = Injekt.get(),
// KMK <--
```

**b) Change download target to temp staging when cloud is active.**

In `downloadChapter()` around line 350, BEFORE `val mangaDir = provider.getMangaDir(...)`:

```kotlin
// KMK -->
val isCloudStorage = CloudStorageType.fromValue(
    storagePreferences.cloudStorageType().get()
) != CloudStorageType.LOCAL

// When cloud storage is active, use a temp staging directory in app cache
// instead of the real downloads directory
val mangaDir = if (isCloudStorage) {
    val stagingBase = File(context.cacheDir, "cloud_staging")
    val sourceDir = File(stagingBase, provider.getSourceDirName(download.source))
    val mangaFolder = File(sourceDir, provider.getMangaDirName(download.manga.ogTitle))
    mangaFolder.mkdirs()
    UniFile.fromFile(mangaFolder)!!
} else {
    provider.getMangaDir(download.manga.ogTitle, download.source).getOrElse { e ->
        download.status = Download.State.ERROR
        notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
        return
    }
}
// KMK <--
```

**c) After download completes, upload to cloud then clean staging.**

After line 448 (`cache.addChapter(chapterDirname, mangaDir, download.manga)`), add:

```kotlin
// KMK -->
if (isCloudStorage) {
    val sourceDirName = provider.getSourceDirName(download.source)
    val mangaDirName = provider.getMangaDirName(download.manga.ogTitle)
    val chapterFilename = if (downloadPreferences.saveChaptersAsCBZ().get()) {
        "$chapterDirname.cbz"
    } else {
        chapterDirname
    }
    cloudUploadManager.uploadChapter(
        stagingDir = mangaDir,
        chapterFilename = chapterFilename,
        sourceDirName = sourceDirName,
        mangaDirName = mangaDirName,
    )
    // Clean up staging area after successful upload
    mangaDir.findFile(chapterFilename)?.delete()
    // Also delete the staging manga dir if empty
    if (mangaDir.listFiles()?.isEmpty() == true) mangaDir.delete()
}
// KMK <--
```

**d) Force CBZ when cloud is active.**

Around line 440 where `downloadPreferences.saveChaptersAsCBZ().get()` is checked:
```kotlin
// KMK -->
if (isCloudStorage || downloadPreferences.saveChaptersAsCBZ().get()) {
// KMK <--
    archiveChapter(mangaDir, chapterDirname, tmpDir)
} else {
    tmpDir.renameTo(chapterDirname)
}
```

---

#### 4. `DownloadCache.kt` — Cloud-Aware Cache Renewal

**File**: `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCache.kt`

**In `renewCache()`** (line ~394), add a cloud path alongside the local path:

After the existing local directory traversal (around line 430-464), add a cloud listing block:

```kotlin
// KMK -->
// When cloud storage is active, fetch listings from cloud instead of local filesystem
val cloudClientFactory: CloudStorageClientFactory by injectLazy()
val cloudStoragePrefs: StoragePreferences by injectLazy()

if (CloudStorageType.fromValue(cloudStoragePrefs.cloudStorageType().get()) != CloudStorageType.LOCAL) {
    val cloudClient = cloudClientFactory.create()
    if (cloudClient != null) {
        val basePath = cloudStoragePrefs.cloudBaseFolderPath().get().trimEnd('/')
        val downloadsPath = "$basePath/downloads"

        // List source directories from cloud
        val sourceFolders = cloudClient.listFolder(downloadsPath).getOrNull()
            ?.filter { it.isDirectory } ?: emptyList()

        for (sourceFolder in sourceFolders) {
            val sourceId = sourceMap[sourceFolder.name.lowercase()] ?: continue
            val sourceDir = updatedRootDir.sourceDirs.getOrPut(sourceId) {
                SourceDirectory(null)
            }

            // List manga directories
            val mangaFolders = cloudClient.listFolder(sourceFolder.path).getOrNull()
                ?.filter { it.isDirectory } ?: continue

            for (mangaFolder in mangaFolders) {
                val mangaDir = sourceDir.mangaDirs.getOrPut(mangaFolder.name) {
                    MangaDirectory(null)
                }

                // List chapter directories/files
                val chapters = cloudClient.listFolder(mangaFolder.path).getOrNull() ?: continue
                for (chapter in chapters) {
                    val chapterName = when {
                        chapter.name.endsWith(Downloader.TMP_DIR_SUFFIX) -> null
                        chapter.isDirectory -> chapter.name
                        chapter.name.endsWith(".cbz") -> chapter.name.removeSuffix(".cbz")
                        else -> null
                    }
                    chapterName?.let { mangaDir.chapterDirs += it }
                }
            }
        }
    }
}
// KMK <--
```

**Performance note:** PCloud supports `listfolder?recursive=1` which returns the entire tree in one call. For PCloud, optimize by using a single recursive call instead of nested calls. Add to `PCloudClient`:
```kotlin
suspend fun listFolderRecursive(path: String): Result<JsonObject> {
    return apiCall("listfolder", mapOf("path" to path, "recursive" to "1"))
}
```
Then parse the nested `contents` arrays to build the full tree in one network round-trip.

---

#### 5. `DownloadPageLoader.kt` — Cloud-Aware Page Loading

**File**: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/DownloadPageLoader.kt`

**Modify `getPages()`** (line 34) to fall back to cloud cache when chapter not found locally:

```kotlin
override suspend fun getPages(): List<ReaderPage> {
    val dbChapter = chapter.chapter
    var chapterPath = downloadProvider.findChapterDir(
        dbChapter.name,
        dbChapter.scanlator,
        dbChapter.url,
        /* SY --> */ manga.ogTitle, /* SY <-- */
        source,
    )

    // KMK -->
    // If not found locally and cloud storage is active, fetch from cloud cache
    if (chapterPath == null) {
        val storagePreferences: StoragePreferences by injectLazy()
        val cloudChapterCache: CloudChapterCache by injectLazy()
        val cloudType = CloudStorageType.fromValue(storagePreferences.cloudStorageType().get())
        if (cloudType != CloudStorageType.LOCAL) {
            val sourceDirName = downloadProvider.getSourceDirName(source)
            val mangaDirName = downloadProvider.getMangaDirName(manga.ogTitle)
            val chapterDirName = downloadProvider.getChapterDirName(
                dbChapter.name, dbChapter.scanlator, dbChapter.url,
            )
            chapterPath = cloudChapterCache.ensureChapterAvailable(
                sourceDirName, mangaDirName, chapterDirName,
            )
        }
    }
    // KMK <--

    return if (chapterPath?.isFile == true) {
        getPagesFromArchive(chapterPath)
    } else if (chapterPath != null) {
        getPagesFromDirectory(chapterPath)
    } else {
        emptyList()
    }
}
```

**Note:** `DownloadProvider.getSourceDirName()` and `getMangaDirName()` are currently private or internal. They need to be made `internal` or `public` so `DownloadPageLoader` can access them. Check their visibility and adjust if needed.

---

#### 6. `DownloadManager.kt` — Cloud-Aware Delete

**File**: `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadManager.kt`

**In `deleteChapters()`** (around line 234), after local deletion, add cloud deletion:

```kotlin
// KMK -->
val storagePreferences: StoragePreferences by injectLazy()
val cloudClientFactory: CloudStorageClientFactory by injectLazy()
val cloudChapterCache: CloudChapterCache by injectLazy()

if (CloudStorageType.fromValue(storagePreferences.cloudStorageType().get()) != CloudStorageType.LOCAL) {
    val client = cloudClientFactory.create()
    if (client != null) {
        val basePath = storagePreferences.cloudBaseFolderPath().get().trimEnd('/')
        val sourceDirName = provider.getSourceDirName(source)
        val mangaDirName = provider.getMangaDirName(manga.ogTitle)
        val remoteMangaPath = "$basePath/downloads/$sourceDirName/$mangaDirName"

        filteredChapters.forEach { chapter ->
            val chapterDirName = provider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url)
            // Delete both possible formats from cloud
            client.delete("$remoteMangaPath/$chapterDirName.cbz", isDirectory = false)
            client.delete("$remoteMangaPath/$chapterDirName", isDirectory = true)
            // Also clear local read cache
            cloudChapterCache.removeChapter(sourceDirName, mangaDirName, chapterDirName)
        }
    }
}
// KMK <--
```

**In `deleteManga()`** (around line 275), add:
```kotlin
// KMK -->
if (CloudStorageType.fromValue(storagePreferences.cloudStorageType().get()) != CloudStorageType.LOCAL) {
    val client = cloudClientFactory.create()
    val basePath = storagePreferences.cloudBaseFolderPath().get().trimEnd('/')
    val sourceDirName = provider.getSourceDirName(source)
    val mangaDirName = provider.getMangaDirName(manga.ogTitle)
    client?.delete("$basePath/downloads/$sourceDirName/$mangaDirName", isDirectory = true)
}
// KMK <--
```

---

#### 7. `AppModule.kt` — Register New Singletons

**File**: `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt`

Add after the existing KMK block (around line 183):

```kotlin
// KMK -->
addSingletonFactory { CloudStorageClientFactory(get(), get()) }
addSingletonFactory { CloudUploadManager(get(), get()) }
addSingletonFactory { CloudChapterCache(app, get(), get()) }
// KMK <--
```

Add imports:
```kotlin
import eu.kanade.tachiyomi.data.cloud.CloudStorageClientFactory
import eu.kanade.tachiyomi.data.cloud.CloudUploadManager
import eu.kanade.tachiyomi.data.cloud.CloudChapterCache
```

---

#### 8. `strings.xml` — UI Strings

**File**: `i18n-kmk/src/commonMain/moko-resources/base/strings.xml`

Add these strings:

```xml
<!-- Cloud Storage -->
<string name="pref_storage_type">Storage type</string>
<string name="cloud_storage_local">Local storage</string>
<string name="cloud_storage_pcloud">PCloud</string>
<string name="cloud_storage_webdav">WebDAV</string>

<!-- PCloud settings -->
<string name="pref_pcloud_access_token">PCloud access token</string>
<string name="pref_pcloud_base_folder">Base folder path</string>
<string name="pref_pcloud_eu_region">Use EU region (eapi.pcloud.com)</string>

<!-- WebDAV settings -->
<string name="pref_cloud_webdav_url">WebDAV server URL</string>
<string name="pref_cloud_webdav_username">Username</string>
<string name="pref_cloud_webdav_password">Password</string>
<string name="pref_cloud_webdav_base_path">Base path</string>

<!-- Cloud actions -->
<string name="cloud_test_connection">Test connection</string>
<string name="cloud_test_connection_success">Connection successful!</string>
<string name="cloud_test_connection_fail">Connection failed: %s</string>
<string name="cloud_uploading">Uploading to cloud…</string>
<string name="cloud_download_error">Failed to download from cloud: %s</string>
```

---

## Implementation Order

Execute in this order (each phase is independently testable):

### Phase 1: Foundation (no behavior change)
1. Modify `StoragePreferences.kt` — add enum + preferences
2. Create `CloudStorageClient.kt` — interface only
3. Add strings to `strings.xml`

### Phase 2: API Clients (testable via unit tests)
4. Create `PCloudClient.kt`
5. Create `WebDavClient.kt`
6. Create `CloudStorageClientFactory.kt`
7. Register in `AppModule.kt`

### Phase 3: Settings UI (user can configure + test connection)
8. Modify `SettingsDataScreen.kt` — storage type picker + config fields + test button

### Phase 4: Cloud Download (chapters upload to cloud after download)
9. Create `CloudUploadManager.kt`
10. Modify `Downloader.kt` — temp staging + cloud upload hook + force CBZ

### Phase 5: Cloud Reading (read chapters from cloud)
11. Create `CloudChapterCache.kt`
12. Modify `DownloadPageLoader.kt` — cloud fallback

### Phase 6: Cloud Cache & Delete (full lifecycle)
13. Modify `DownloadCache.kt` — cloud directory listing in renewCache()
14. Modify `DownloadManager.kt` — cloud-aware delete

---

## Key Technical Details

### PCloud API Specifics (US region: api.pcloud.com)
- Auth: `?access_token=TOKEN` on every request
- All responses: `{"result": 0, ...}` on success, non-zero on error
- `createfolderifnotexists` — idempotent folder creation (use this, not `createfolder`)
- `getfilelink` → returns `{hosts: [...], path: "..."}` → construct URL `https://{hosts[0]}{path}` → HTTP GET for file content
- `uploadfile` — multipart POST, `path` param = parent folder, file in multipart body
- `listfolder?recursive=1` — returns entire tree in one call (use for DownloadCache)
- `deletefolderrecursive` — for deleting manga/chapter directories

### WebDAV Specifics
- Auth: Basic auth header on every request (`Credentials.basic(user, pass)`)
- `PROPFIND` with `Depth: 1` for directory listing, parse XML multistatus
- `MKCOL` for mkdir (ignore 405 = already exists)
- `PUT` for file upload, `GET` for download, `DELETE` for removal
- `MOVE` with `Destination` header for rename/move
- Reference: existing `WebDavSyncService.kt` in the project uses identical patterns

### Cloud Directory Structure
```
<basePath>/
  downloads/
    <source_name>/          ← provider.getSourceDirName(source)
      <manga_title>/        ← provider.getMangaDirName(manga.ogTitle)
        <chapter>.cbz       ← provider.getChapterDirName(...) + ".cbz"
```
Mirrors the local structure exactly so DownloadCache name matching works unchanged.

### Error Handling Strategy
- Network errors during upload: Download stays in staging, retry on next download trigger
- Network errors during read: Show error toast, reader shows empty/error state
- Network errors during cache renewal: Use last-known cache from disk serialization
- Auth errors: Show toast prompting user to re-check credentials in settings
