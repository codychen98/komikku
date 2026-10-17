# Cloud Library extension

Installable Tachiyomi/Mihon/Komikku extension that reads the `komikku-library/` layout written by the Komikku fork cloud upload pipeline.

## Sources

| Source | ID |
|--------|-----|
| Cloud Library (WebDAV) | `7001748234567890001` |
| Cloud Library (pCloud) | `7001748234567890002` |

IDs must match the fork migrate targets. Do not change them.

## Build

This is a standalone Gradle project (not part of the Komikku multi-module build). CI builds it on every `master` push and PR via:

```bash
./gradlew -p cloud-library-extension assembleRelease
```

Artifacts:
- Push CI: `cloud-library-extension-release`
- PR CI: `cloud-library-extension-<sha>`

Local (from repo root, with the root `gradlew`):

```bash
./gradlew -p cloud-library-extension assembleRelease
```

If `keystore.properties` exists at the repo root (or in this folder), the release APK is signed with that keystore.

Install the APK, then configure credentials under Browse → Extensions → Cloud Library. Fork cloud prefs (Settings → Data → Cloud downloads) are separate in v1.

## Layout (read-only)

```
<root>/komikku-library/<MangaDirName>/
  ComicInfo.xml
  cover.jpg
  <ChapterDirName>/
    001.jpg ...
    ComicInfo.xml
```

All writes happen in the Komikku fork. This extension never uploads or deletes.
