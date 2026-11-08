# 2026-11-06 — 008 — PhotoXT Phase 4: Sync and cache lifecycle

- **Tool:** Claude (Cowork), cloud session linked to robin-laptop, 2026-11-06 to 2026-11-08. Built and tested in the cloud workspace (Linux, JDK 21, Android SDK 37); Drive changed on drive.google.com in Robin's Chrome; the Galaxy A15 driven over wireless adb through WPSC on robin-laptop; delivered into this folder.
- **Goal:** Build Phase 4 of [photoxt-build-prompt.md](../photoxt-build-prompt.md).

## Scope
Incremental Drive sync through the Changes API with periodic and foreground triggers; rename, move, delete and content changes; orphan purge and the unavailable-folder state. The cache: the 30-day retention worker, the originals cap and the preview LRU, offline pins, prefetch, data saver and the Storage screen. Checks: unit and screenshot tests, a migration test, then the "Done when" items on the phone with a small test folder changed on the Drive website.

## Decisions (asked)
- Robin chose the Drive test: a folder "PhotoXT sync test" with 10 generated photos in his My Drive, added in PhotoXT on the A15, then copied, renamed and binned files on drive.google.com in his Chrome, pulling to refresh after each; the folder goes to the Drive bin afterwards.
- No library outside §5.1 was added: DataStore 1.3 and WorkManager 2.12.
- The rest are in [memory.md](../memory.md): settings as JSON in a typed DataStore, Room v4, the content-version rule, the Changes feed's rules, orphans and their 7-day grace, sync triggers, WorkManager on demand, the cache rules, offline pins, data saver, and the Phase 4 gaps.

## Done
- `core/datastore` (new): `SettingsStore` with `PxtSettings.storage`: retention days (30), originals cap (5 GB), preview cap (1 GB), prefetch (2 each side), and originals on mobile data (Always / Wi-Fi only / Ask; default Wi-Fi only).
- `core/database`: Room v4 with auto-migration 3→4 (`offline_pin`, `drive_orphan`, `tracked_folder.offline`; `drive_cache.offlinePinned` dropped), the schema export, the DAO queries for the Changes feed, pins, orphans and the cache rules, and the Offline filter.
- `core/sync`: `DriveChanges` (the Changes feed per account, one transaction a page with its token; renames retitle albums and paths, structural changes mark a tree for a rescan, a tracked folder binned or gone turns unavailable, an expired token rescans), `DriveItems` (one write path for scans and changes: content-version rule, orphans for new content, restore from the bin), `OfflinePins`, `SyncWork` (periodic sync, daily cleanup, offline downloads; workers through a Hilt entry point), and `DriveSync` reworked: a lock per account, a foreground trigger, `refresh` for pull-to-refresh, `relink`, and settling (pins, due orphans, thumbnails) after each sync or scan. `DriveScanner` writes through `DriveItems`, sweeps into orphans and marks a binned root unavailable. `DeviceConditions` reports metered networks.
- `core/media`: `DriveCache` (cleanup with retention, caps and video retention by span touch time; *Free up now*; *Clear cache for this album*; usage per account and album; orphan purge), `DriveOriginals` (offline folder, moves into it, prefetch, preview access time for the LRU), `DriveThumbnails.preview` (Drive's 1,600 px rendering for data saver), `DriveVideo` (sizes, removal by key, trim by touch time), `ThumbnailStore` (`media-offline/`).
- `feature/library` (new): the Library tab with a Storage row, and the Storage screen: the phone's total and its parts, per account and album with an expandable breakdown and *Clear cache for this album*, *Free up now*, and the cache settings. Sizes are measured each time the screen shows.
- `feature/timeline` and `feature/albums`: pull-to-refresh in Pictures' albums and in Albums (a toast when it fails); an Offline filter chip; "Make available offline" in a Drive album's menu; the unavailable banner with *Relink* (the Drive browser picks the folder again for the same album) and *Remove*.
- `feature/viewer` and `feature/player`: "Keep offline" in the viewer's menu; prefetch of the neighbours' originals (not on metered networks unless originals always load); data saver opens the preview with *Load original* (and *Always* when set to Ask); the player plays a kept or downloaded video from its file.
- `app`: the Library tab, the Storage destination, Relink routing, WorkManager configured on demand.
- Fixed after the phone checks (below): the Storage screen kept the sizes it measured when first opened, because a hidden tab keeps its screens; and the cap labels read "5.4 GB" and "1.1 GB", because the caps were binary megabytes under Android's decimal size format. Caps are now decimal (labels "5.0 GB", "1.0 GB").
- Tests (99 in total, 81 before): `DriveCacheTest` with a fake clock (an item opened 31 days ago returns to thumbnail-only and a pinned one doesn't; the cap evicts least recently opened first; previews stay under their cap; files of items removed in Drive wait out the grace period), `DriveChangesTest` on the fake Drive (adds, renames, moves and removals; a changed file regenerates its thumbnail; an item back from the bin gets its files and pin back; a binned tracked folder keeps its items until it comes back; a removal whose tracked folder is gone keeps the item; a new folder or an item moved out marks a rescan; an expired position starts over), `MigrationTest` 3→4, `SettingsSerializerTest`, `StorageContentTest`, and three screenshot tests (unavailable Drive album, Storage at 100% and 200% text).

## Checks run
- The CI command, `spotlessCheck detekt lintDebug testDebugUnitTest assembleDebug :baselineprofile:assemble`, green after the last change: 99 tests passed, no Lint or detekt findings. Screenshot goldens re-recorded only for the new and changed screens (Library tab, unavailable album, Storage).

## On the phone (Galaxy A15 5G, One UI 8 / Android 16, wireless adb through WPSC)
- Test data: "PhotoXT sync test" in My Drive with `sync-test-01.jpg` to `-10.jpg` (generated JPEGs, taken 1 Oct 2026 10:00–19:00), added in PhotoXT: 10 items.
- Add: "Make a copy" of `sync-test-01.jpg` on the web, pull to refresh: 11 items.
- Rename: `sync-test-05.jpg` renamed on the web to `renamed-on-web-05.jpg`, pull to refresh: the details sheet shows the new name. Its thumbnail and stored version stayed (no download), though Drive raised the file's version.
- Remove: `sync-test-04.jpg` moved to the Drive bin, pull to refresh: 10 items. Restored from the bin, pull to refresh: 11 items, with its old thumbnail file back unchanged (the orphan restore).
- Cache: opening photo 5 fetched its neighbours' originals (prefetch); the Storage screen listed the account and its albums with their parts, and *Clear cache for this album* brought the test album from 254 kB to its 35 kB of thumbnails.
- Offline: "Keep offline" on photo 10 put its original (46,872 bytes) in `media-offline/`, the Offline chip showed only that photo, and Storage showed "Kept offline 47 kB".
- Unavailable folder: the whole test folder moved to the Drive bin, pull to refresh: the album showed "This Drive folder is no longer available…" with *Relink* and *Remove*, and kept its items. *Remove* deleted the album with its thumbnails and the offline original.
- The two Storage fixes were installed and checked: the limits read "5.0 GB" and "1.0 GB", and after a preview was deleted behind the app's back while another tab showed, returning to Library showed Previews 1.6 MB instead of 1.8 MB.
- Not tried on the phone: data saver (the phone was on Wi-Fi throughout, and marking it metered is a system setting), a kept-offline video, shared drives (none on these accounts), and the 6-hourly periodic run (WorkManager ran the daily cleanup and the first sync at app start).
- Screenshots of the check are in `build/cowork/p4/shots` (not in git).
- Afterwards: the final debug build is on the phone with Robin's account; the test album is removed in the app; the "PhotoXT sync test" folder is in Robin's Drive bin; the helper scripts are gone from the phone; the Chrome tab used for Drive is closed.

## Phase 4 "Done when"
- Fake-clock tests prove an item opened 31 days ago returns to thumbnail-only, a pinned item doesn't, and a changed file regenerates its thumbnail: met (`DriveCacheTest`, `DriveChangesTest`).
- Adding, removing or renaming a file in Drive on the web shows up after pull-to-refresh: met on the A15 (above), plus restoring from the bin and binning the whole folder.

## §7 Definition of done
- Build and tests: green locally (above); GitHub Actions runs once this commit is pushed. No Lint or detekt warnings.
- Offline: kept-offline items have their originals on the phone and stay through *Free up now*, the retention rule and the caps (tests); thumbnails and BlurHashes as in Phase 3.
- Accessibility: new controls have labels and roles from strings.xml (the Offline chip, "Keep offline" and "Make available offline" as checkboxes, the Storage choices as drop-down lists, the banner's buttons); the Storage screen holds at 200% text (screenshot test). Not tried with TalkBack.
- Logging: debug build, the whole journey above: of 1,844 lines from PhotoXT's process none holds a token, e-mail address, file or folder name or URL; `DriveSync` logs only counts ("Synced 1 changes: 0 written, 1 removed, …"). The one new StrictMode report is One UI's `Toast.show` checking a file (memory.md).
- Records: [memory.md](../memory.md), this log and the README status line.

## Skipped, and when to add
- The sync interval and network rules as settings (§4.21): Phase 6 with Settings; until then sync runs every 6 hours on any network and background thumbnails pause on metered networks (`ponytail:` in DeviceConditions).
- The Phase 4 gaps in memory.md: a shortcut deleted for good waits for a rescan; Slideshow and Compare ignore data saver; "Syncing" shows only for scans; nested albums of an unavailable tree show no banner.

## Problems met
- Maven Central answered 429 for a Robolectric jar once; the rerun found it cached.
- Gradle runs longer than the tool's 2-minute limit died with it; builds then ran in the background. The cloud workspace restarted once midway (Robin re-sent "Build Phase 4") and the interrupted test run was repeated.
- detekt found 19 issues on the first run: config changes for function counts and annotated constructors (memory.md), the rest fixed in the code.
- Gradle 9 fails a module whose tests find nothing, so `feature/library` got `StorageContentTest` at once.
- `adb devices` through WPSC started the adb server inside the channel and held it; Robin ended adb.exe, and the server now starts detached.
- Typing through `cmd`: `input text` with `%s` was eaten by cmd's `%` expansion, and uiautomator quoting broke, so the phone ran small scripts from `/data/local/tmp`.
- On drive.google.com a right-click landed on empty space after the page shifted while loading; it was retried on the file.
- The phone check found the two Storage bugs fixed above.
