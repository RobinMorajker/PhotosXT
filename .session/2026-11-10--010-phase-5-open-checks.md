# 2026-11-10 — 010 — PhotoXT Phase 5: the open phone checks

- **Tool:** Claude (Cowork), cloud session linked to robin-laptop, 2026-11-10 to 2026-11-11. Built and tested in the cloud workspace (Linux, JDK 21, Android SDK 37); the Galaxy A15 driven over wireless adb through WPSC on robin-laptop; Drive checked through the Google Drive connector and Robin's Chrome.
- **Goal:** Robin asked to "build phase 5". Phase 5 was already built and committed (session 009), so he chose to close the phone checks it left open here, and to build Phase 6 afterwards in a subagent.

## Scope
The checks session 009 left to fakes or skipped: copy and move between two Google accounts, a Drive file the user can't bin, batch rename and Export as PDF on the phone, the "needs you" notification, and the WorkManager path. Fix what they find, with a test wherever the logic can break.

## Decisions (asked)
- Robin let the session take over the phone, and approved adding robinmorajker2@gmail.com to PhotoXT, a test album in each account's Drive, and sharing the robinmorajker test folder with robinmorajker2 as an editor. The share was made in his Chrome with "Notify people" off, because the Drive connector can't change sharing ("The caller does not have permission").
- Test data: the phone folder `Pictures/PhotoXT ops test2`, with seven generated photos (XA_01–XA_07, EXIF dates with an offset) and two generated videos: XA_VID, 62 MB, and XA_BIG, 313 MB. Each video is a 10 s H.264 clip padded with a `free` box built on the phone. In Drive, the folders "PhotoXT ops A" (robinmorajker) and "PhotoXT ops B" (robinmorajker2), both made with PhotoXT's *New album*.

## Checks on the phone (Galaxy A15 5G, One UI 8 / Android 16)
- The phone ran the APK of 2026-11-09 17:25 UTC, older than the Phase 5 commit: its Undo row still read "Move to …". The build of e3bab73 was installed over it (same debug key, data kept) before the checks below, except the first two pastes.
- Two accounts. Copy A→B of the 62 MB video and two photos streamed down and back up; their MD5s in Room equal the phone originals'. A cut A→B of a photo was copied, verified, then binned in A. Undo from Transfers restored it from A's bin and binned B's copy; the row read "Undo: Move to PhotoXT ops B".
- Not yours to bin. robinmorajker2 added the shared "PhotoXT ops A" as an album (Shared with me). Delete on robinmorajker's photo asked "1 item isn't yours to delete". Keep them skipped it. Remove from album took it out of the folder, and Drive moved it to the owner's My Drive root, but the job showed as failed (bug 3).
- robinmorajker2 also cut robinmorajker's photo from the shared folder into robinmorajker2's own folder. It went through as a plain parents update: Drive lets an editor move the owner's file into the editor's folder, where the owner can't see it. Recorded in memory.md; no change.
- Batch rename. Three phone photos became `{date}_{n}` (20240115_01.jpg …), with the same MediaStore ids and dates, after MediaStore's "modify" screen, which came up twice (bug 6). Three Drive items became `trip_{n}` (trip_01.jpg, trip_02.mp4, trip_03.mp4).
- Export as PDF. Four photos went through the system save screen to Download: a 4-page A4 PDF, each photo fitted to its page.
- WorkManager path and "needs you":
  - Copied XA_BIG and XA_06 to album A, sent PhotoXT home, cancelled its user-initiated job (`cmd jobscheduler cancel`) and killed the process (`am kill`) at 16 MB.
  - Force-ran WorkManager's sync job (`cmd jobscheduler run -f -n androidx.work.systemjobscheduler com.photoxt.app <id>`), which started PhotoXT in the background.
  - TransferWorker resumed the upload from Drive's offset: one 313,260,442-byte file, under the pre-made id.
  - XA_06's name conflict then raised "PhotoXT needs you · 1 transfer waits for your answer". Tapping it opened Transfers with the question; Skip cleared it.
  - Android refused the worker's foreground service from the background (`code:DENIED`), so no progress showed (bug 5).

## Bugs found, all fixed
1. Drive's Changes feed lists a write a few seconds late. The sync after a paste missed its last item, which stayed hidden until pull-to-refresh. `DriveSync.syncAfterWrite` now syncs again 5 s and 30 s later.
2. An account whose first Drive grant was full access (its first act a *New album*) got a second consent screen, for read-only access, when a folder was added. Its background token, which asks for read-only, would have flagged it for Reconnect. `DriveScope.toAsk` now asks for full Drive when it is held.
3. *Remove from album* on someone else's file worked, but Drive answers 204 with no body once the file is out of sight, and the job failed on the missing body. `DriveApi.removeParent` reads no answer.
4. Renaming in Drive moved videos, and any file without an EXIF time, to the rename's date, because Drive sets modifiedTime to now on a rename. A rename now sends the file's old modifiedTime.
5. The WorkManager path showed no progress when Android refused its foreground service. Progress now goes out as a plain notification under the same id, which also updates the service's own notification when there is one, and is cleared at the end.
6. A batch rename of three photos asked MediaStore twice: the first photo alone, then the other two. The prompt now waits a second for the batch to settle and asks once.
7. Transfers: a new batch landed above the visible list; it now stays in view when the list was at the top. A batch whose only item was skipped read "Done · 0 items"; it now reads "Skipped · 1 item".

## Tests
- `TransferSafetyTest`: the fake Drive now answers a not-owned removal with 204, as Drive does, and the job must end DONE. The new `renamingInDriveKeepsTheTimeAVideoIsDatedBy` uses a fake that dates renames now unless told otherwise. Both fail without their fixes (checked with the fixes stashed).
- `GoogleAccountsTest.anAccountWithFullDriveIsNeverAskedForReadOnly`.
- 139 tests in all (137 before).

## Checks run
- The CI command, `spotlessCheck detekt lintDebug testDebugUnitTest assembleDebug :baselineprofile:assemble`: green, 139 tests, no Lint or detekt findings. Screenshot goldens unchanged.
- Re-checked on the phone with the fixed build:
  - Bug 1: four photos pasted into a Drive album all showed without a refresh; the follow-up sync 5 s later brought the fourth.
  - Bug 4: renaming a video to `clip_{n}` kept its modifiedTime and its date.
  - Bug 3: Remove from album ended "Done · 1 item".
  - Bug 7: Keep them read "Skipped · 1 item", and an Undo row appeared in view at the top.
  - Bug 6: a rename of three photos asked once ("modify 3 photos?").
  - Bug 5: the background run showed "Copying to PhotoXT ops A" with progress, then cleared it.
  - Bug 2: robinmorajker2 synced with the new token request and showed no Reconnect.
- Logging: 564 lines from PhotoXT's last process and 246 tagged lines in the phone's whole buffer. None holds a token, e-mail address, file or album name, path, content URI or Drive id.

## Accessibility
- The one new string ("Skipped · …") is in strings.xml. No new controls. Not tried with TalkBack.

## Problems met
- WPSC hung on the first command: a bare `adb devices` started the adb server, which held the channel's output pipe. Task Manager runs elevated and ignored computer-use clicks, so Robin ended adb.exe. `build/cowork/p5c/adbx.ps1` and `ph.ps1` now start the server detached first.
- Wireless debugging switched itself off once while the phone stayed on Wi-Fi, and Robin turned it back on. The phone also showed up twice over mDNS.
- Maven Central answered 429 to the sandbox, for Gradle and for Robolectric's own jar download. Google's mirror worked through `~/.gradle/init.d`. Background Gradle runs died until started with `setsid`.

## Afterwards (2026-11-11)
- Drive: robinmorajker2's access to "PhotoXT ops A" removed. That folder and the two photos moved out of it were binned through the connector. robinmorajker2's "PhotoXT ops B" was binned in Chrome. Everything sits in the two bins until Drive deletes it after 30 days.
- Phone: the test folder, its nine files and the exported PDF deleted, with no MediaStore rows left. robinmorajker2 removed from PhotoXT (its grants revoked), and the unavailable album removed. Transfers cleared, the files in `/data/local/tmp` deleted, and "stay awake while charging" turned off again.
- Laptop: the test data, screenshots, database copies and the copies of the debug keystore and `gradle.properties` deleted from `build/cowork/p5c`. The last APK and the helper scripts stay.
