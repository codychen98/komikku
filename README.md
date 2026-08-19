# Disclaimer

This is a personal, unofficial fork provided as-is. I am not responsible for any data loss, device issues, or other problems that may occur from using this software. Use at your own risk.

# Komikku (Custom Fork)

A personal fork of [Komikku](https://github.com/komikku-app/komikku) with additional features.

## Fork Features

1. **Custom Chapter Order** — Manually reorder chapters using drag-and-drop (including multi-select move to top/bottom).
2. **Chapter Exclusion** — Permanently hide specific chapters from the list; restore them from filters when needed.
3. **Orphaned Chapter Restoration** — Keep downloaded chapters in the database when the site removes them; reindex restores older orphans. Supports **nested chapter folders** on disk, ComicInfo-aware matching on reindex/merge, and cleaner orphan cleanup after merge. When a source **URL changes**, retained downloads and orphan rows reconcile to the new listing where possible.
4. **Backup via Intent** — Trigger backups programmatically via ADB or automation (e.g. Tasker / MacroDroid), tested on rooted device. Each backup also writes a Mihon-readable sibling file.
5. **Hitomi filtering fix** — Restores working Hitomi filters by fixing `QuerySanitizer` behavior that upstream changes broke.
6. **Ignore duplicated chapters (reworked)** — In **Settings → Reader**, turn **Skip duplicate chapters** on to skip duplicated same chapters while you read. On each manga’s chapter list, **Skip sub-chapter duplicates** is optional and only shows when that global setting is on; if you turn it on for that manga, the reader also skips sub-chapters (for example 3.1 when 3.0 exists).
7. **Exclude Sources from Library Update** — Skip chosen sources during global library refresh (manual updates still allowed).
8. **Clear Cache via Intent** — Clear app caches and related database data in one broadcast for automation.
9. **Browse feed behavior** — Browse keeps loaded feeds across navigation, fetches additional rows when needed instead of reloading on every navigation hop, and raises the per-source row cap from **20 to 30**. CopyManga: saved filters apply reliably on the first load.

---

## How to Use

### 1. Custom Chapter Order

1. Open a manga’s detail page.
2. Tap the sort icon and choose **Custom order**.
3. Tap the **lock icon** next to the chapter count to enter reorder mode.
4. Drag with the grip handle, or long-press to multi-select and use **Move to top** / **Move to bottom**.
5. Tap **Save order** or **Discard**.
6. To reset: in sort settings, tap **Reset custom order**.

### 2. Chapter Exclusion

1. Long-press chapters to select them.
2. Tap **Remove** in the bottom bar.
3. Removed chapters stay hidden across source sync until restored.
4. To show them: chapter filter settings → enable **Show removed chapters**.
5. Select removed chapters → **Restore**.

### 3. Orphaned Chapter Restoration

When a site drops a chapter from its listing, Komikku normally deletes the DB row even if files remain on disk. This fork changes that:

- **Automatic:** Downloaded chapters are kept in the database during sync.
- **Manual:** **Settings → Data & Storage → Reindex downloads** (and related Library **Advanced** actions where reindex/merge is exposed) restores orphans from disk, including **nested chapter directories**. Reindex/merge uses URL and ComicInfo-style matching to attach orphans to catalog chapters, drop false duplicate orphan rows, merge read progress where appropriate, and tidy empty folders after moves.

### 4. Backup via Intent

Example (MacroDroid + root shell with a custom path):

```
am broadcast -a app.komikku.CREATE_BACKUP -n app.komikku/eu.kanade.tachiyomi.data.backup.BackupBroadcastReceiver --es export_path "/storage/emulated/0/Download/Sync Folder/PCloud Sync/Backup"
```

Each run writes `komikku.tachibk` (full Komikku backup) and `mihon.tachibk` (Mihon-readable copy) in that folder and replaces those files if they already exist.

Restore `komikku.tachibk` in Komikku. Restore `mihon.tachibk` in Mihon. Mihon will not restore Komikku-only data such as custom chapter order, excluded chapters, or Browse feeds.

In-app **Create backup** and automatic backups also write a sibling file next to the Komikku backup when the folder allows it (`app.komikku_DATE.tachibk` plus `app.mihon_DATE.tachibk`).

### 5. Ignore Duplicated Chapters (reworked)

1. **Settings → Reader** → turn **Skip duplicate chapters** on to skip duplicated same chapters in the reader.
2. Optional, per manga: when that is on, open the chapter list and turn **Skip sub-chapter duplicates** on for that title if you also want sub-chapters skipped (for example 3.1 when 3.0 exists) when using next/previous.

### 6. Exclude Sources from Library Update

1. **Settings → Library** → **Excluded sources**.
2. Select sources to skip during automatic library updates (multi-language variants are grouped).
3. Excluded sources can still be updated manually.

### 7. Clear Cache via Intent

```
am broadcast -a app.komikku.CLEAR_CACHE -n app.komikku/eu.kanade.tachiyomi.data.cache.ClearCacheBroadcastReceiver
```

Clears non-library manga from the database, chapter cache, and page preview cache (as implemented by the receiver).

## To use this fork while keeping your data (rooted device only)

1. Back up app data (e.g. Neo Backup).
2. Uninstall the original app.
3. Install this build.
4. Restore app data only.
