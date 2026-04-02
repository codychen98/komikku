# Disclaimer

This is a personal, unofficial fork provided as-is. I am not responsible for any data loss, device issues, or other problems that may occur from using this software. Use at your own risk.

# Komikku (Custom Fork)

A personal fork of [Komikku](https://github.com/komikku-app/komikku) with additional features.

## Fork Features

1. **Custom Chapter Order** — Manually reorder chapters in any order using drag-and-drop.
2. **Chapter Exclusion** — Permanently hide specific chapters from the list.
3. **Orphaned Chapter Restoration** — Restore chapters that were downloaded locally but removed from the manga site.
4. **Backup via Intent** — Trigger backups programmatically via ADB or automation tools like Tasker/Macrodroid, tested on rooted device.
5. **Hitomi filtering Fix** — Fixed QuerySanitizer that broke Hitomi filtering.
6. **Ignore Duplicated Chapters (Reworked)** — Enhanced the existing duplicate chapter detection to automatically skip sub-chapters (e.g., 3.1, 3.2) when a parent chapter (e.g., 3.0) exists during reader navigation.
7. **Exclude Sources from Library Update** — Prevent specific manga sources from being updated during library refresh.
8. **Clear Cache via Intent** — Clear app caches and database in one operation via broadcast intent for automation.

---

## How to Use

### 1. Custom Chapter Order

1. Open a manga's detail page.
2. Tap the sort icon and select **Custom order**.
3. Tap the **lock icon** next to the chapter count to enter Reorder Mode.
4. Drag chapters using the grip handle on the right, or long-press to multi-select and use **Move to top** / **Move to bottom**.
5. Tap **Save order** to keep changes, or **Discard** to revert.
6. To reset, open the sort settings and tap **Reset custom order**.

### 2. Chapter Exclusion

1. Long-press chapters to select them.
2. Tap **Remove** in the bottom action bar.
3. Removed chapters are hidden and won't reappear on source sync.
4. To view removed chapters: open chapter filter settings and enable **Show removed chapters**.
5. Select removed chapters and tap **Restore** to bring them back.

### 3. Orphaned Chapter Restoration

When a manga site removes a chapter from its listing, Komikku normally deletes the database record even if the files are still on disk. This feature fixes that:

- **Automatic:** Downloaded chapters are now preserved in the database during sync.
- **Manual:** Go to **Settings → Data & Storage → Reindex downloads** to restore chapters that were orphaned before this fix was applied.

### 4. Backup via Intent

With a custom output path in Macrodroid using shell script in root mode:

```
am broadcast -a app.komikku.CREATE_BACKUP -n app.komikku/eu.kanade.tachiyomi.data.backup.BackupBroadcastReceiver --es export_path "/storage/emulated/0/Download/Sync Folder/PCloud Sync/Backup"
```

### 5. Ignore Duplicated Chapters (Reworked)

The original app had a **Skip duplicate chapters** setting, but it didn't work well with sub-chapters. This rework improves it:

1. Go to **Settings → Reader**.
2. Enable **Skip duplicate chapters**.
3. When reading, the reader will now automatically skip sub-chapters (e.g., 3.1, 3.2, 3.3) if a parent chapter (e.g., 3.0) exists during navigation.
4. The chapters still remain in your chapter list, but the reader's next/previous buttons will skip over them.
5. This is useful for sources like MangaFire that duplicate content across sub-chapters.

### 6. Exclude Sources from Library Update

1. Go to **Settings → Library**.
2. Tap **Excluded sources**.
3. Check the sources you want to exclude from automatic library updates.
4. Multi-language variants (e.g., MangaDex English/Chinese) are grouped as one source.
5. Excluded sources won't update during global library refresh, but can still be updated manually.

### 7. Clear Cache via Intent

Clear all caches in one operation using ADB or automation tools:

```
am broadcast -a app.komikku.CLEAR_CACHE -n app.komikku/eu.kanade.tachiyomi.data.cache.ClearCacheBroadcastReceiver
```

This clears: non-library manga from database, chapter cache, and page preview cache.

## To use this fork while keeping your data (rooted device only)
1. You can backup your app data using Neo Backup
2. Uninstall the original app
3. Install the new app
4. Restore just the app data
