# Komikku (Custom Fork)

A personal fork of [Komikku](https://github.com/komikku-app/komikku) with additional features.

## Fork Features

1. **Custom Chapter Order** — Manually reorder chapters in any order using drag-and-drop.
2. **Chapter Exclusion** — Permanently hide specific chapters from the list without deleting downloads.
3. **Orphaned Chapter Restoration** — Restore chapters that were downloaded locally but removed from the manga site.
4. **Backup via Intent** — Trigger backups programmatically via ADB or automation tools like Tasker/Macrodroid, tested on rooted device.
5. **Hitomi filtering Fix** — Fixed QuerySanitizer that broke Hitomi filtering

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
