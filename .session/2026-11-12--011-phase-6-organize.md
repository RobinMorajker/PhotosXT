# 2026-11-12 — 011 — PhotoXT Phase 6: organize, history and settings sync

- **Tool:** Claude (Cowork), cloud session linked to robin-laptop, 2026-11-12 to 2026-11-15. Built and tested in the cloud workspace (Linux, JDK 21, Android SDK 37) in a git worktree of `main` at 7c96cf2; the laptop reached through WPSC and the Cowork device bridge.
- **Goal:** Robin asked to "build Phase 6" (spec §6, Phase 6: favorites, pins and shortcuts, bookmarks, history, hidden items and the vault, album groups, virtual and smart albums, covers, sorting everywhere, settings sync), plus what earlier phases left for it: the sync interval and mobile-data rule as settings, and pinch density and the viewer's options persisted.

## Decisions (asked)
- Vault: once the encrypted copy reads back the same, the original is deleted for good, on the phone and in Drive (no plain copy left in a bin).
- Phone checks: drive the phone with generated test files, removed afterwards; Robin's fingerprint or PIN is needed once for Hidden and the vault.

## Built
- **Data (Room v6, auto-migration 5→6):** Date added, the other date keys and natural name keys on `media`, `album.createdAt` and `rule`, and nine tables: album options, groups, virtual-album items, Custom order, pins, bookmark collections and bookmarks, history, hidden items. Resume points lost their foreign key, so a restore can bring them back before their items are indexed. The migration makes the phone and Drive index again to fill the new columns.
- **Sorting (§4.2):** Date taken, Date added, Date modified, Name (natural), Size and Custom order, both ways, for Pictures, every album (its own sort, or the default that *Apply to all albums* sets) and each Library list; the Albums tab by Custom order, Name, Newest item, Date created, Item count and Total size. Date sorts keep date headers for their own date, the others show a plain grid with the fast scroller's bubble on the name's initial or the size. Pinned albums and items stay on top in every sort. Custom order: *Edit order* to drag, Move up/down for TalkBack.
- **Favorites:** a heart in the grid's selection bar and the viewer, synced both ways with MediaStore's IS_FAVORITE and Drive's `starred` through the transfer queue (MediaStore's favorite screen, Drive's `drive` scope); a change that fails takes the heart back. Library → Favorites.
- **Pins and shortcuts:** pin albums to the top of Albums and items to the top of their album, reorder pins by dragging; Camera and Screenshots pinned automatically once. *Add to home screen* pins a launcher shortcut that opens the album.
- **Bookmarks:** items and albums into named collections with notes, video moments from the player (open at that time), "No longer available" with a kept Drive thumbnail. Library → Bookmarks: rename, note, delete.
- **History:** views with their album, watched time, *Recently viewed*, *Continue watching*, "Viewed N times" in Details, pause (incognito), auto-delete after 3 or 12 months or never, clear all or a range of days. Hidden and vault items are never logged.
- **Hidden and the vault:** hide items and albums (out of Pictures, albums and lists), *Hide from Pictures* for an album; Library → Hidden (items and albums, *Unhide*) behind the fingerprint or screen lock. *Move to vault* encrypts with Tink (key sealed by Android Keystore), checks the copy, then deletes the original; Library → Vault behind the lock with screenshots blocked, a viewer for its photos and videos, *Move out of the vault* and *Delete forever*.
- **Albums:** groups (a group tile, its own screen, ungroup), virtual albums (*Add to album* from the selection and the viewer), smart albums from rules (type, years, source, album, camera, size, video length, favorites, has a location), cover options (*Set as album cover*, reset).
- **Settings sync:** one JSON file in the backup account's Drive app-data folder: settings, sort choices, pins, bookmarks, album options and groups, PhotoXT's own albums, hidden items, history, resume points and the tracked Drive folders. Uploads 15 s after a change; a reinstall restores everything when its account signs in again, says so in a snackbar, and adds back the Drive albums.
- **Settings screen:** Drive background sync interval and mobile data, history controls, settings sync (account, status, *Allow*, *Sync now*), Storage.
- **Persisted now:** pinch density (Pictures and albums), the viewer's display mode and zoom lock, the slideshow's options.

## Tests
- `SortTest` (every item sort both ways over a sample album: natural names, Date added independent of Date taken, pinned items on top in every sort, grid = viewer = positions), `AlbumSortTest` (every album sort both ways, pins on top, empty and new albums first), `SortSpeedTest` (20,000 items, every sort under 1 s on the JVM: 150–370 ms).
- `HistoryTest` (hidden items and albums never logged, hiding forgets, incognito, auto-delete, day ranges), `SnapshotTest` (a reinstall gets pins, bookmarks, sorts and settings back; unchanged state isn't sent again), `OrganizeOpsTest` (favorites both ways and taken back on failure; vault in from the phone and from Drive with nothing left behind, out again), `VaultTest` (round trip, no plain bytes or names on disk, another key reads nothing, files can't be swapped, video seeks), `MigrationTest` 5→6, screenshot tests of the five new Library pages at 100% and 200% text.

## Problems met
- `VaultTest` found that Tink's keyset-level seekable channel can't tell its size before its first read, which would have broken every vault video: `VaultDataSource` now reads a byte first.
- The remote-devices bridge dropped twice (502) mid-session and came back; WPSC kept working throughout.

## Checks run
- The CI command, `spotlessCheck detekt lintDebug testDebugUnitTest assembleDebug :baselineprofile:assemble`: green, 185 tests (139 before), no Lint or detekt findings.
- Screenshot goldens re-recorded for intended changes only: a Sort button in the Pictures, album and Albums headers, *Album* and *Favorite* in the selection bar, the Library's new rows, and the five new Library pages (Bookmarks, History, Hidden, Vault, Settings) at 100% and 200% text.
- Logging: the new log lines are "Restored settings from Drive", "Settings synced (N KiB)", the vault's "A vault item couldn't be read", the settings-sync failures, and in debug builds "Sort <field> <up/down>: first rows in N ms". None carries a name, path, address or token, and all go through `PxtLog`.

## Phone checks: still to do
- The Galaxy A15 didn't show up over wireless adb from robin-laptop on 2026-11-15 (no mDNS services, no devices): Wireless debugging is probably off, and only Robin can turn it on. The Phase 6 debug APK is in `build/cowork/p6/app-debug.apk` (SHA-256 8ef9f9b8…fd70, signed with the laptop's debug key).
- To check on the phone: the 5→6 upgrade over the Phase 5 build (data kept, the library indexed again for Date added); sort switches on a 20,000-item album under 1 s (the debug log's "first rows" times) and scrolling without jank (`dumpsys gfxinfo`); favorites both ways with Samsung Gallery and drive.google.com; pins, a home-screen shortcut; a bookmark moment; history, incognito; Hidden and the vault behind Robin's unlock, the vault's files read as ciphertext through `run-as`; settings sync's file in the app-data folder, then uninstall, reinstall and sign in: pins, bookmarks, sorts and settings back.

## Accessibility
- Every new control has a TalkBack name: the heart, Sort, the album and tile menus' entries, pin and drag handles, *Move up/down* in Custom order, the bookmark and vault menus ("Options for …"), *Unhide …*, the player's "Bookmark this moment", and the lock's Unlock button. Switches are toggleable rows with the Switch role.
- The new Library pages hold at 200% text (screenshot tests). Not yet tried with TalkBack on the phone.
