# 2026-11-04 — 004 — PhotoXT Phase 1: Device gallery

- **Tool:** Claude (Cowork), cloud session linked to robin-laptop, 2026-11-04 to 11-05. Built and tested in the cloud workspace (Linux, JDK 21, Android SDK 37); the Galaxy A15 was driven over wireless adb through WPSC on robin-laptop; delivered into this folder.
- **Goal:** Build Phase 1 of [photoxt-build-prompt.md](../photoxt-build-prompt.md).

## Scope (spec §0 rule 2)
MediaStore indexer (generation scans, deletion diff, full rescan on a new MediaStore version) and Room v1; index passes on every return to the app and on MediaStore changes while it is open; permission flows (full, limited, denied); Pictures timeline (Paging, date headers, pinch density, fast scroller, filters); Albums grid and the grid of one album; multi-select with drag-select, and Share. Checks: indexer, query and layout tests, gesture and TalkBack tests, screenshots, and measurements on the phone against the "Done when" list.

## Decisions (asked)
- No library outside §5.1 was added: Room, Paging, Coil 3, Lifecycle and Coroutines are all listed. core-ktx was already on the classpath through Activity; it is now explicit because Lint wants `toUri()`.
- Phone checks run on Robin's Galaxy A15, which Robin reconnected. Robin chose adding 20,000 test images and deleting them afterwards; the phone already held 23,781 items on two volumes, so its own library was used and no test images were added.
- Stay awake was on while testing and is off again.
- The rest are in [memory.md](../memory.md): modules, Room v1, ids, timeline query, density levels, indexer, thumbnails, gestures, selection, permissions.

## Done
- `core/model`: media, album, access and filter types; `DateGrouping` (day, month and year keys in the local calendar).
- `core/database`: Room v1 (`album`, `media`, `media_store_cursor`) with the exported schema; the timeline as one UNION query (each date header, then its items) paged with placeholders and jumps; group counts, per-group stamps for select-all, share targets, album summaries with covers.
- `core/media`: `MediaStoreIndexer` (per volume: generation scan, id diff, direct fetch of unseen ids, full rescan on a new MediaStore version or time zone, never deletes after a failed query); `DeviceMediaSync` (a pass on every resume and on permission answers, a content observer while open, bursts coalesced); `MediaAccessMonitor`; `DeviceThumbnailFetcher` (MediaStore's own thumbnails through Coil, at most 4 reads at a time).
- `core/ui`: `MediaGrid` with grid-level gestures (tap, long-press, drag-select with edge auto-scroll, pinch between year, month and 7–1 day columns that keeps the same photo in view); tiles with video length, favorite and selection marks; date headers that select a whole date; `FastScroller` with a date bubble, adjustable by TalkBack; access request and limited-access banner (Manage, Allow all).
- `core/designsystem`: the header takes a navigation icon and actions; new icons (back, close, share, play, favorite, selection).
- `feature/timeline`: Pictures and the grid of one album; filters (All, Photos, Videos, Favorites); selection mode with a count in the header, the navigation bar swapped for a Share bar, back to cancel; Share of up to 500 items through the system sheet.
- `feature/albums`: Albums grid with Camera and Screenshots first, translated names for recognised folders.
- `app`: album navigation, the Coil image loader with the thumbnail fetcher, sync started with the app, a Baseline Profile generated on the phone (`app/src/main/baseline-prof.txt`).
- `baselineprofile`: shared journeys (grant media access, fling Pictures), the profile generator now scrolls the grid, and `ScrollBenchmark`.
- Tests (43 in total, 13 in Phase 0): `MediaStoreIndexerTest` (9), `TimelineQueryTest` (5), `TimelineLayoutTest` (4, including drag ranges), `MediaGridTest` (long-press and tap, TalkBack actions and select-all, pinch both ways), `RecogniseFolderTest`, `ShareTest`, and screenshots of Pictures, selection, limited access and Albums, three of them at 200% text.

## Checks run
- The CI command, split into two Gradle runs here because the sandbox kills a daemon that runs everything at once: `spotlessCheck detekt lintDebug`, then `testDebugUnitTest assembleDebug :baselineprofile:assemble`. Both green; every unit test re-run with `--rerun`, 43 passed, screenshots verified against the goldens.

## On the phone (Galaxy A15 5G, SM-A156E, One UI 8, wireless adb through WPSC)
- Access: denied shows the request; limited shows the banner and only the 3 picked items; after Allow all, 23,781 items were indexed in about 12.5 s (debug build).
- New photo while PhotoXT is open, saved into DCIM/Camera and scanned as a camera app does: indexed 1.96 s and 0.78 s after the scan request, and on screen at the top of Today.
- Photo deleted elsewhere while PhotoXT was in the background: gone 1.7 s after returning to the app. A delete while it is open is picked up in about 0.8 s.
- Scrolling: `ScrollBenchmark`, release build, 8 full-speed flings through all 23,781 items, 5 runs each. Frame time is CPU time per frame; overrun below zero is time to spare.

| Run | Build | Thumbnails | Frame time P50 / P90 / P95 / P99 (ms) | Overrun P50 / P90 / P95 / P99 (ms) |
|---|---|---|---|---|
| 1 (11-04) | no profile | software, `loadThumbnail` | 6.4 / 10.9 / 13.6 / 19.5 | −0.2 / 4.3 / 7.6 / 16.4 |
| 2 (11-04, phone warm after profiling) | with profile | hardware, Coil | 8.4 / 17.0 / 22.0 / 34.8 | 1.9 / 11.5 / 19.9 / 39.7 |
| 3 (11-05) — shipped | with profile | software, Coil | 6.3 / 11.9 / 15.1 / 23.8 | −0.2 / 5.8 / 9.6 / 22.1 |
| 4 (11-05, right after run 3) | with profile | hardware, Coil | 8.1 / 15.6 / 19.1 / 32.8 | 1.6 / 9.4 / 14.2 / 33.9 |

  Traces: with hardware bitmaps the render thread freed graphic buffers 535 times in one 8-fling iteration (up to 11 ms each) and the main thread waited on it; with software bitmaps that is gone. In run 3, 25 of 1,302 frames (1.9%) missed the app deadline; nearly all others were on time but queued one frame behind (Perfetto's "buffer stuffing", smooth with a little latency). The remaining misses are rows composing mid-fling and a full GPU queue.
- Logging (§7): debug build, cold start and flings. 360 lines came from PhotoXT's process; none contains a file name, path, content URI, place or token. StrictMode reported main-thread disk reads and writes during startup; none has a PhotoXT frame in its stack (framework and library code, as in Phase 0).
- Afterwards: the debug build is back on the phone, the benchmark app and its outputs are removed, Stay awake is off, and the helper files are gone from `/data/local/tmp`.

## Phase 1 "Done when"
- 20,000 photos scroll without visible jank: met on the A15 with 23,781 items (run 3 above). The stricter §5.8 budget, under 1% janky frames at 120 Hz, is measured in Phase 15; full-speed flings here miss about 2%.
- A new camera photo appears within 2 s while the app is open: met (0.78 s and 1.96 s).
- A photo deleted in another app is gone on the next resume: met (1.7 s after resume).
- Tests cover the incremental indexer: `MediaStoreIndexerTest`, 9 tests: first pass; a quick pass after an insert skips the id diff; an edited item is updated; a deleted item is removed on the next pass; a new MediaStore version forces a full rescan; a new time zone moves day keys; items that become visible with old generations are fetched by id; an emptied album and an unmounted volume are dropped; a failed query deletes nothing.

## §7 Definition of done
- Build and tests: green locally (above); GitHub Actions runs once this commit is pushed. No Lint or detekt warnings.
- Offline: Phase 1 uses no network at all; the app has no INTERNET permission.
- Accessibility: tiles and headers expose TalkBack actions (open, select, toggle, select a whole date), tested in `MediaGridTest`; the fast scroller is adjustable like a slider. Screenshots at 200% text hold for Pictures with the limited-access banner, the access request and Albums; album names end in "…" at that size. Not tried with TalkBack on the phone itself, which this session cannot drive.
- Logging and records: above, plus [memory.md](../memory.md) and the README status line.

## Skipped, and when to add
- Tapping a tile to open it: Phase 2 (viewer). Tiles already expose the open action.
- Keeping the grid density across launches: Settings (Phase 6); it is kept in saved state now.
- Device, Drive and Offline filter chips: Phase 3, when Drive albums exist.
- Selection actions other than Share (delete, move, favorite, add to album): Phases 5 and 6.
- `ACCESS_MEDIA_LOCATION` for photo locations: with the viewer's info panel.
- The under-1%-janky scroll budget: Phase 15's performance pass.

## Problems met
- The first live test caused a 16,362-item pass: ids Room had never seen made the indexer rescan everything. It now fetches those ids directly, and the full id diff runs only on deletes, resumes and permission answers; that brought a new photo down to under 2 s.
- Hardware bitmaps made scrolling worse on the A15 (table above); the image loader now turns them off.
- One UI 8 shows only the photo picker when asked again, with no "Allow all"; the banner's Allow all opens the app's settings instead.
- WPSC hung when `adb install` left adb's server holding the output pipe; long phone commands now run detached (memory.md).
- Copying into `/sdcard` does not reach MediaStore; test photos went through `scan_file`, as an app saves them.
- Test files under `build/cowork` synced to the phone's SD card and showed up in its galleries; that folder now has a `.nomedia` file.
- Gradle 9 fails a test task that has sources but finds no tests; `feature/timeline` got its first test.
- The session's file transfer rejects files over about 8 MB; the debug APK went across in 8 MB parts, joined on the laptop.

## Next
- Try PhotoXT on the phone: the debug build is installed.
- Push this commit (`git push`) so CI runs on it at GitHub; Phase 0 is already there.
- Still open from Phase 0: exclude `build`, `.gradle` and `.kotlin` from the folder sync.
- `build/cowork` (ignored by git) holds test APKs, benchmark logs and traces, and `screens-p1/` with screenshots of the phone's library; delete the folder when no longer needed.
- Phase 2 (Viewer and player).
