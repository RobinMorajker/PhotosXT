# 2026-11-05 — 006 — PhotoXT Phase 3: Google account and Drive albums

- **Tool:** Claude (Cowork), cloud session linked to robin-laptop, 2026-11-05 to 2026-11-06. Built and tested in the cloud workspace (Linux, JDK 21, Android SDK 37); Google Cloud set up in Robin's Chrome; the Galaxy A15 driven over wireless adb through WPSC on robin-laptop; delivered into this folder.
- **Goal:** Build Phase 3 of [photoxt-build-prompt.md](../photoxt-build-prompt.md).

## Scope (spec §0 rule 2)
3a, a throwaway spike on the phone: AuthorizationClient scope requests and the upgrade to `drive`, re-consent in Testing mode, listing a folder picked under `drive.file`, `thumbnailLink` sizes and expiry, authorised Range streaming in ExoPlayer, Changes API coverage for shared-with-me folders and shared drives. 3b: Credential Manager sign-in with several accounts, an in-app Drive folder browser, tracked folders as albums in both subfolder modes, the initial scan with Drive thumbnails stored on the phone and BlurHash placeholders, streamed photos and videos (SimpleCache), and the `MediaSource` abstraction. Checks: unit and screenshot tests, a migration test, then the three "Done when" items on the phone with a generated 2,000-photo folder and a 1 GB video.

## Decisions (asked)
- Robin chose: Claude sets up Google Cloud in his Chrome; a generated test folder in his My Drive (2,000 photos and a 1 GB video), deleted after the checks; his second account (robinmorajker2@gmail.com, on the A15) for the shared-with-me test; Claude taps through account and consent screens on the phone over adb.
- Robin agreed to the Google API Services User Data Policy when the consent screen was set up, and opened Chrome so Claude could add the second account as a test user.
- No library outside §5.1 was added: Credential Manager + Google ID, Play services Auth, OkHttp + Retrofit (a thin hand-written Drive interface), kotlinx.serialization, Media3's OkHttp data source, MockWebServer, and a small BlurHash util.
- The rest are in [memory.md](../memory.md): the Google Cloud project, the Web client id property, modules, accounts and tokens, Room v3, ids, scan, thumbnails, originals, video, `MediaSource`, and the spike's findings.

## Done
- Google Cloud project **PhotoXT** (`photoxt`): Drive API, Google Auth Platform (External, Testing, two test users), scopes, an Android client for the laptop's debug key and a Web client for Credential Manager.
- `core/auth`: `GoogleAccounts` (Credential Manager sign-in; the account id is the credential's unique id, else the ID token's `sub`), `DriveAuth` (AuthorizationClient per account, scope upgrade, consent completion, 45-minute in-memory token, `invalidate` on 401, `revoke` on account removal, `needsConsent` when a grant lapses).
- `core/drive`: `DriveApi` (files.list/get, changes, start token, shared drives; `driveLiteral` escaping), `DriveHttp` (one OkHttp client, per-account auth interceptor with a single retry after 401, ranged `open`), the Drive models and field lists.
- `core/database`: Room v3 with auto-migration 2→3 (`account`, `tracked_folder`, `drive_folder`, `drive_thumbnail`, `drive_cache`, Drive columns on `album` and `media`), `DriveDao`, Albums now lists top-level albums and Drive trees, the timeline and viewer queries carry the source.
- `core/sync`: `DriveScanner` (start token first, breadth-first listing, shortcuts, nested or merged albums, sweep), `DriveSync` (add, rescan, merge switch, remove folder or account with their files, background thumbnail fill), `DeviceConditions` (online, metered, power saving).
- `core/media`: `MediaSource` with `DeviceSource` and `DriveSource`, `ThumbnailStore` (spec §5.6 layout), `ThumbnailQueue` (visible first), `DriveThumbnails` (link, EXIF, video frame or whole file → WebP + BlurHash), `BlurHash`, `DriveOriginals` (resumable, MD5-checked downloads and previews), `DriveVideo` (SimpleCache over OkHttp), Coil fetchers and keyers.
- `feature/albums`: "Add Drive folder" and "Google accounts" in the header, the accounts sheet (pick, add, remove, reconnect), the Reconnect banner, Drive badges and status on album tiles, and the Drive browser (My Drive, Shared with me, Shared drives, Starred, search, shortcuts, "Merge subfolders into one album", Add).
- `feature/timeline`: Drive albums with a child-album row, sync and consent status, an overflow with the merge switch and "Remove from PhotoXT"; filters "On this phone" and "Drive" in Pictures.
- `feature/viewer` and `feature/player`: Drive photos open from the downloaded original with the BlurHash and progress meanwhile; Drive videos stream through the cache.
- `core/designsystem`: 15 new Material Symbols Rounded icons.
- Fixed after the phone checks (below): an expired thumbnail link was never renewed, because `files.get` asked for fields without `id`; the keyboard now closes when a Drive search is submitted; and two disk reads moved off the main thread (the viewer's lookup of a Drive photo's preview, and opening the video cache when a Drive video starts), which debug StrictMode had reported.
- Tests (81 in total, 61 before): `MigrationTest` 2→3, `BlurHashTest`, `ThumbnailQueueTest`, `DriveOriginalsTest` (Range resume, MD5 mismatch), `DriveThumbnailsTest` (expired link renewed), `DriveScannerTest` (a fake Drive on MockWebServer: nested tree, merge rescan), `GoogleAccountsTest`, `DriveHttpTest` (401 retry, header stripped, query escaping), updated `TimelineQueryTest` and `TimelineCellTest`, and four new screenshot tests (Albums with Drive albums at 100% and 200% text, the Drive browser, a folder in it at 200%).

## Checks run
- The CI command in two Gradle runs, as before: `spotlessCheck detekt lintDebug`, then `testDebugUnitTest --rerun assembleDebug :baselineprofile:assemble`. Both green: 81 tests passed, no Lint or detekt findings.

## On the phone (Galaxy A15 5G, One UI 8 / Android 16, wireless adb through WPSC)
- Test data, generated on the laptop and in the cloud and uploaded with Drive for desktop to `My Drive/PhotoXT test`: 1,500 photos at the top, `Trip 2025` with 300 and its subfolder `Day 2` with 200 (JPEGs of about 117 KB with EXIF dates, each labelled "PhotoXT test #n"), and `Videos` with a 1,060,725,695-byte, 10-minute 1080p video with its time burnt in and its `moov` atom at the end.
- Sign-in: "Add Google account" opened Credential Manager's account sheet; the Drive browser then asked for `drive.readonly` (Testing shows "Google hasn't verified this app" first). The browser listed and searched folders.
- Adding "PhotoXT test" (nested): the album was on screen 3.4 s after the tap, and the scan finished in 10.6 s: 4 folders, 2,001 items. All 2,001 grid thumbnails were made 3 min 26 s later (37 MB on disk); the database had 2,001 BlurHashes and no failures.
- A Drive photo opened sharp from its downloaded original (MD5 checked); its neighbours' originals and previews were fetched alongside.
- 1 GB video: the player read the `moov` from the file's end (497 KiB), played from the start, and after a tap on the seek bar at 70% showed 00:07:10.000 within about 3 s. The cache then held bytes 0–365 MB and 751–932 MB plus the tail; 365–751 MB were never fetched, 534 MB of 1,061 MB in all.
- Second account: signed in from the accounts sheet; "Add Drive folder" then asked which account, and the second account's consent led to its own Drive.
- Re-consent: after the spike revoked the grant, Albums showed "Google Drive needs your permission again for …" with Reconnect; Reconnect asked for read access only, and the banner went.
- 200% text: the album header with its child albums (names end in "…", counts wrap), the filter chips (scroll sideways) and the Albums list hold. The bottom bar's labels wrap mid-word, as recorded in Phase 2. Font size back at Robin's 0.8 afterwards.
- Spike findings are in [memory.md](../memory.md); the spike lived in `app/src/debug` and was deleted before the commit.
- Afterwards: the final debug build is on the phone with Robin's main account signed in; the second account and the "PhotoXT test" album were removed in the app (its thumbnails and downloads went with it) and the video cache cleared; the Drive folder "PhotoXT test" is in Robin's Drive bin and the second account's spike folder in its bin; Stay awake is off, the font size is back at 0.8, and the helper files are gone from the phone. The generated files are still in `C:\Users\Robin Morajker\PhotoXT-test-data` (2.3 GB, most of it the video and its parts) for Robin to delete.

## Phase 3 "Done when"
- A 2,000-file Drive folder shows a browsable album within 60 s: met, 3.4 s to the album and 10.6 s to a complete scan of 2,001 items.
- Its thumbnails stay visible in airplane mode: met. Robin turned on airplane mode and scrolled the whole album: every tile showed its photo. (Wireless adb can't run this check, since airplane mode cuts it.) He noticed the tiles are cropped, "otoXT test #…": grid tiles are square centre crops, as for the phone's own photos since Phase 1, so a 4:3 photo loses an eighth on each long side; the stored thumbnails are the whole photo (512 × 384 or 384 × 512).
- A 1 GB video seeks without being fully downloaded: met (above), 534 MB of 1,061 MB fetched after playing 2 minutes and seeking to 7:10.

## §7 Definition of done
- Build and tests: green locally (above); GitHub Actions runs once this commit is pushed. No Lint or detekt warnings.
- Offline: Drive thumbnails, BlurHashes and downloaded originals are on the phone, so the album browses in airplane mode (above); a photo not yet downloaded would show its BlurHash and a failure message (that path was not tried offline). Device albums don't use the network.
- Accessibility: every new icon button has a label from strings.xml; Drive tiles read as "<name>, <n> items" and show their state in text as well as by icon; 200% text holds (above and in the screenshot tests). Not tried with TalkBack.
- Logging: debug build, a journey through the Drive album, two Drive photos, the Drive video with a seek, the Drive browser with a search, and the account sheet. Of 1,120 lines from PhotoXT's process none holds a token, e-mail address, file or folder name, Drive URL or coordinates. After the two fixes above no StrictMode report has a PhotoXT frame; the rest come from a library's SharedPreferences cleanup, One UI's `FactoryTest` and `IdsController`, and one untagged OkHttp socket.
- Records: [memory.md](../memory.md), this log and the README status line.

## Skipped, and when to add
- Background and periodic sync (Changes API, WorkManager), the 30-day cache rule and cache caps: Phase 4. The video cache is a plain 1 GB LRU until then (`ponytail:` in DriveVideo).
- Writing to Drive (move, delete, rename, share) and the upgrade to `drive` on first write: Phase 5 onwards; the spike proved the upgrade (`ponytail:` in MediaSources, where `DriveSource` is read-only).
- Share, Open with and the 360° view for Drive items, and subtitles for Drive videos: with the phases that bring sharing and Drive file access beyond reading.
- Pinning Drive items offline (`media-offline/`) and settings for metered networks: Phase 4 and Phase 6 (`ponytail:` in DeviceConditions).
- A fixed six thumbnail downloads at a time (`ponytail:` in DriveThumbnails); tune if thumbnails trail a fast scroll on slow links.
- Shared drives: browsing and scanning use the all-drives flags, but no account here has a shared drive, so none was tried.

## Problems met
- Maven Central rate-limited the cloud's Gradle again; the mirror init script in the cloud workspace got round it.
- `GoogleIdTokenCredential` fields are nullable and `id` is deprecated (warnings are errors); the account id is `uniqueId`, falling back to the ID token's `sub`.
- Gradle 9 fails a module whose test sources find no tests, so each new module got its tests early.
- Robocopy started through WPSC lost its quoting and copied nothing; a script file run with `-File` worked. A first checksum of the joined video ran while the copy was still writing.
- The Cowork device shell writes to the connected folder at about 0.2 MB/s and its background processes die with each call, so the 1 GB video was made in the cloud and moved with `SendUserFile` + `device_commit_files` (memory.md).
- An adb server started inside a WPSC command held the channel open; Robin ended it, and adb now runs detached.
- The Chrome extension disconnected midway, so the second test user waited for Robin to open Chrome.
- On the phone, a tap meant for the Drive app's share dialog landed on Developer options, which Robin had opened, and switched on "Bug report shortcut"; it was switched back off.
- The Drive link-refresh bug above was found by the spike, when `files.get` with only `thumbnailLink,hasThumbnail` failed to parse.
