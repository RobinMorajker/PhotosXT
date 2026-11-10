# 2026-11-08 — 009 — PhotoXT Phase 5: File operations

- **Tool:** Claude (Cowork), cloud session linked to robin-laptop, 2026-11-08 to 2026-11-10. Built and tested in the cloud workspace (Linux, JDK 21, Android SDK 37); the Galaxy A15 driven over wireless adb through WPSC on robin-laptop; Drive checked through the Google Drive connector; delivered into this folder.
- **Goal:** Build Phase 5 of [photoxt-build-prompt.md](../photoxt-build-prompt.md).

## Scope
The clipboard and its paste bar; the §4.7 operation matrix; a transfer engine (Room queue, resumable and verified transfers, user-initiated jobs or WorkManager with notifications, a Transfers screen); name conflicts, Undo and a unified Trash; New and rename for albums and folders; the incremental `drive` scope upgrade; the media-management flow; batch rename, convert and PDF export. Checks: unit tests against a fake Drive and a fake MediaStore, screenshot tests, a migration test, then the "Done when" items on the phone with generated test files.

## Decisions (asked)
- Robin chose generated test files for the phone check: a phone folder `Pictures/PhotoXT ops test` with 12 generated photos (9 JPEG, 1 HEIC, 1 PNG, 1 WebP) and a 304 MB generated video, and a Drive folder "PhotoXT ops test" in his My Drive, made by PhotoXT's own *New album*; nothing else touched, all removed afterwards.
- Copy and move between two Google accounts: fake Drive only (Robin).
- The `drive` consent on the phone: Claude taps through Google's screens over adb (Robin).
- No library outside §5.1 was added.
- The rest are in [memory.md](../memory.md): `core/transfer`, Room v5, the engine's rules, resuming, the job that runs the queue and its id range, waits and prompts, the matrix routes, *New album*, the dialog-scope and MediaStore-column lessons.

## Done
- `core/model`: `TransferOp`, `TransferState`, `WaitFor`, `ConflictPolicy`, `ClipboardMode`.
- `core/database`: Room v5 with auto-migration 4→5 (`transfer_batch`, `transfer_job`, `clipboard`, `album.pending`), the schema export, `TransferDao` and `FileOpsDao`; pending albums survive the empty-album sweep until MediaStore shows their folder; empty albums lead the Albums list.
- `core/drive`: Drive writes in `DriveApi` (copy, update with parents and trash, create, delete, generateIds), `DriveUploads` (resumable sessions, chunks, status), and `driveReason()` for 403s.
- `core/auth`: `DriveAuth.hasScope`; a consent asked for more scope no longer flags the account for Reconnect.
- `core/transfer` (new): `TransferEngine` (the drain, outcomes, retries), `Copier` (the matrix, renames, conversions, verification), `JobRun` (one run's progress and shared steps), `TransferQueue` (batches, waits, controls, answers, Undo), `Transfers` (clipboard, paste, trash, restore, delete forever, rename, convert, PDF, new and renamed albums), `PastePlanner`, `DeviceStore` (MediaStore reads and writes), `DriveFiles`, `TrashRepository`, `BatchTools` (rename patterns, JPEG converter, PDF export), `Consents` (MediaStore's request screens, media management, system clipboard), and `TransferRunner` (user-initiated job, WorkManager worker, notifications).
- `feature/timeline`: the selection bar (Share, Cut, Copy, Delete, More: Compare, Rename, Convert to JPEG, Export as PDF, Copy to clipboard), the paste bar, the album menu (New folder, Rename album, Cut and Copy album), their dialogs, and `TimelineFileOps`.
- `feature/albums`: *New album* (on the phone or in a Drive account) and pasting clipped albums at the top.
- `feature/library`: Trash (both bins together, Restore, Delete forever, Empty), Transfers (progress, pause, resume, cancel, retry, Undo, Clear finished), and the media-management row.
- `feature/viewer`: Delete, and Copy to clipboard for Drive photos (downloaded first).
- `app`: the transfer prompts (MediaStore's screens, Google's consent, name conflicts, files the user can't bin), the notification permission, the Undo snackbar, Trash and Transfers destinations, a notification tap opening Transfers, and WorkManager's JobScheduler id range.

## Tests
- 137 tests in all (99 before): `OperationMatrixTest` (14: every matrix cell, plus albums pasted with their subfolders and an album into itself), `TransferSafetyTest` (13: a killed upload resumes in a new engine, a download resumes with Range, three cuts whose copy fails its checksum keep their source, conflicts with Apply to all, Replace and Skip, Undo of a move, a trash and a cut across sources, MediaStore's write grant and trash confirmation, the `drive` scope wait, a file the user can't bin), `BatchToolsTest` (4), `MigrationTest` 4→5, `TimelineQueryTest` (new empty albums first), and five screenshot tests (Trash at 100% and 200% text and while selecting, Transfers at 100% and 200%).

## Checks run
- The CI command, `spotlessCheck detekt lintDebug testDebugUnitTest assembleDebug :baselineprofile:assemble`, green after the last change: 137 tests passed, no Lint or detekt findings. Goldens re-recorded only for the changed screens (Library and Albums tabs, the Albums grids at 100% and 200%, Pictures while selecting) and recorded for the new ones.

## On the phone (Galaxy A15 5G, One UI 8 / Android 16, wireless adb through WPSC)
- Test data: `Pictures/PhotoXT ops test` (`OPS_01`–`OPS_12` and `OPS_BIG.mp4`, 303,978,241 bytes) pushed and scanned; a phone album "PhotoXT ops dest" made with *New album*; the Drive album "PhotoXT ops test" made with *New album* in My Drive, which asked for full Drive access: "Google hasn't verified this app", Continue, then the consent page's Continue after a scroll. A one-photo folder `Pictures/PhotoXT ops sd` on the SD card for the cross-volume move.
- Matrix, one account, each checked on disk or in Drive: phone→phone copy (`OPS_12.webp`, same MD5, owned by PhotoXT); phone→phone cut on one volume (`OPS_11.png` moved, after MediaStore's "Allow PhotoXT to modify this photo?"); phone→phone cut to the SD card (copied, same MD5, then the original binned after "move this photo to bin?"); phone→Drive copy (`OPS_09.jpg`, size and modified time kept); phone→Drive cut (`OPS_08.jpg` uploaded first, the original binned after consent); Drive→phone copy (`OPS_09.jpg`, MD5 equal to the original); Drive→phone cut (downloaded, then binned in Drive); Drive→Drive copy into a new subfolder "sub" (a second file, server-side) and cut (the same file id, new parent).
- Killing mid-transfer: the 304 MB video was copied to Drive again (a name conflict: Keep both made `OPS_BIG (1).mp4`) and PhotoXT force-stopped at 50 MB. Relaunched, the upload did not start (bug 6 below); with the fix installed it went on from Drive's offset (128 MB nine seconds after launch), finished, and Drive held one `OPS_BIG (1).mp4` of the full size. No "Upload session expired" in the log. (A first try was killed 4 s after the upload finished; that job completed by finding its file under its pre-made id, with no duplicate.)
- Conflicts: Keep both (above) and Replace (the item already there went to the trash, then the cut item moved in).
- Trash and Undo: a phone photo moved to the trash after MediaStore's confirmation and came back with Undo from Transfers (after "move this photo out of bin?"); a Drive photo binned and restored with the snackbar's Undo; Undo of the cross-volume cut restored the original and binned the SD copy. The Trash screen listed the test items from both bins; Restore brought `OPS_08.jpg` out of the Drive bin and Delete forever removed the SD copy for good.
- Convert to JPEG on ten selected photos made `OPS_10.jpg` (from HEIC) and `OPS_12.jpg` (from WebP), 2000 × 1500, and left the JPEGs alone.
- Media management: turned on in system settings from Library, a cut of another app's photo went ahead with no confirmation; turned off again afterwards.
- Logging: debug build. None of PhotoXT's lines holds a token, e-mail address, file or album name, path, content URI or a Drive folder id: 2,998 from its last process (22:56–23:23: the resumed upload, conflicts, Trash and Undo, Convert, media management), 21 from the three before it, and 197 the next morning. The phone's main log had already rotated past 22:56, so the lines from the matrix cells were not seen; every Phase 5 log call is a fixed message through `PxtLog`'s redaction.
- Not tried on the phone: two accounts (fakes only), batch rename, Export as PDF (the system save screen), a cut whose copy fails verification (fakes only), the "needs you" notification while hidden, Drive files the user can't bin, and the WorkManager path (PhotoXT was on screen, so the user-initiated job ran).

## Bugs the phone check found, all fixed
1. *New album* never opened the album: the dialog launched the work in its own scope, which ended as it closed. It now uses the screen's scope.
2. The Drive consent prompt stuck: its `LaunchedEffect` changed its own key before the suspending call, cancelling it. It is now keyed on the account and sets `asking` only when Google's screen opens.
3. A new, empty album sat at the very end of hundreds of albums. Empty albums now come first, after the Camera and Screenshots pins.
4. Every copy from the phone failed: reading one MediaStore item asked its images or video collection for `media_type`, which only the files collection has.
5. MediaStore's confirmation came up twice: the prompt cleared its question before the answer was saved. It now remembers each wait (job and time) until the job moves on.
6. A transfer interrupted by a killed app didn't restart at the next start: the start-up check looked only for queued jobs, not for jobs left running. It now kicks the queue for either, and the drain requeues them.
7. An Undo showed under the same name as what it undid. It is now "Undo: …".
- Changed along the way: a phone copy is now verified by the bytes read back rather than MediaStore's size column, which a pending item may not have yet; and Google's consent answer no longer clears the prompt before the jobs move on.

## Phase 5 "Done when"
- Every matrix cell has a passing test against a fake Drive: met (`OperationMatrixTest`, 11 cells), and the nine one-account cells also passed on the A15.
- Killing the app mid-transfer resumes the transfer: met in tests (a new engine resumes at Drive's offset) and on the A15 after bug 6 was fixed.
- A cut never deletes its source when verification fails: met in tests (bad checksums on an upload, a server-side copy and a phone write all keep the source).

## §7 Definition of done
- Build and tests: green locally (above); GitHub Actions runs once this commit is pushed. No Lint or detekt warnings.
- Offline safety: with no network a job waits and retries every minute without using up its attempts; nothing is removed before its copy is verified.
- Accessibility: new controls take their labels from strings.xml (selection and paste bars, menus, dialogs, Trash tiles with name, place and days left, Transfers' buttons); Trash and Transfers hold at 200% text (screenshot tests). Not tried with TalkBack.
- Logging: as above.
- Records: [memory.md](../memory.md), this log and the README status line.

## Skipped, and when to add
- Copy and move between two accounts on real Drive: Robin chose fakes; try with robinmorajker2@gmail.com when a phase needs both accounts.
- PDF export and batch rename on the phone, and the "needs you" notification: next time a phone check touches file operations.
- Drive's bin gives no trash time for My Drive files (`trashedTime` is filled only in shared drives), so Drive entries in Trash show no days left.
- A copy's capture time on the phone is what MediaStore reads from the file at publish: for EXIF without a time offset and more than a day from the file's time, MediaStore keeps none (the generated test photos showed their push time).

## Problems met
- Robolectric jars and plugins came through the Maven Central mirror in `~/.gradle/init.d`; Gradle ran in the background with two workers after the daemon died once (likely memory).
- detekt flagged the first engine and facade as too large and too complex; they were split by job (memory.md) rather than suppressed. Suppressions remain only on the test fakes (their routers and records), and on the engine's per-job error boundary.
- Wireless adb dropped five times; the phone's Wi-Fi address moved between .250 and .103 and Wireless debugging switched off with it. Robin turned it back on. The helper reconnects to the advertised address and picks one transport when the phone shows up twice.
- `adb shell mkdir -p "…/PhotoXT ops test"` split on the spaces and made an empty `Pictures/PhotoXT`; `adb push` made the right folder. `screencap` into `/sdcard` made a MediaStore item ("Phone storage, 1 item"); it was deleted and the helper now writes to `/data/local/tmp`.
- PowerShell over WPSC stripped quotes inside `adb shell` commands, so phone-side commands went base64-encoded (`echo … | base64 -d | sh`) or as scripts in `/data/local/tmp`.
- The first kill came 4 s after a fast upload (6–7 MB/s) had finished; the second, 9 s after the start, caught it at 50 MB.

## Afterwards (2026-11-10)
- Drive: the folder "PhotoXT ops test" (with `sub`, both copies of `OPS_09.jpg`, `OPS_08.jpg`, `OPS_BIG.mp4` and `OPS_BIG (1).mp4`) went to the bin through the Drive connector. PhotoXT then showed the album as no longer available, and *Remove* took it out of PhotoXT. The files sit in Drive's bin (and PhotoXT's Trash) until Drive deletes them after 30 days.
- Phone: the three test folders (`Pictures/PhotoXT ops test`, `Pictures/PhotoXT ops dest`, the SD card's `Pictures/PhotoXT ops sd`), 19 files with their bin entries, and the empty `Pictures/PhotoXT` deleted and rescanned; MediaStore has no row left for them. Transfers cleared, the helper scripts and logs in `/data/local/tmp` deleted. Media management was already off.
- Laptop: the generated test files, the phone screenshots and the copies of the debug keystore and `gradle.properties` (byte-identical to the originals in the home folder) deleted from `build/cowork/p5`; the last APK, the repo bundle and the helper scripts stay.
