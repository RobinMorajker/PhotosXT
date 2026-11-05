# 2026-11-05 — 005 — PhotoXT Phase 2: Viewer and player

- **Tool:** Claude (Cowork), cloud session linked to robin-laptop, 2026-11-05. Built and tested in the cloud workspace (Linux, JDK 21, Android SDK 37); the Galaxy A15 was driven over wireless adb through WPSC on robin-laptop; delivered into this folder.
- **Goal:** Build Phase 2 of [photoxt-build-prompt.md](../photoxt-build-prompt.md).

## Scope (spec §0 rule 2)
Viewer (new `feature/viewer`): pager over the grid's own order and filter; pinch, pan and fling up to 4× beyond 1:1 with tiled decoding, two-finger rotate, double-tap Fit → Fill → 1:1; display modes Fit, Fill, Stretch, Fit width, Fit height and 1:1; zoom lock; Compare; details sheet with EXIF and a mini map. Open from the tile and close by swipe-down or predictive back into it. Formats: GIF/WebP, Google and Samsung motion photos, Ultra HDR, 360° photo spheres. Slideshow. Player (new `feature/player`, Media3): gestures, speed, PiP, side-loaded subtitles, resume (Room v2). Checks: unit tests for parsing, order and resume logic, a migration test, a zoom benchmark on a 200 MP photo, then the four "Done when" items on the phone.

## Decisions (asked)
- Map: Robin chose to build the mini map now and add the key later. The key comes from the Gradle property or environment variable `PHOTOXT_MAPS_API_KEY`, never git (README). Without it the sheet shows the coordinates and Open in Maps.
- Test files: Robin chose adding test files to a "PhotoXT test" folder on the phone and deleting them afterwards. Done: added, used, deleted.
- The Phase 1 build on the phone was signed with that session's throwaway debug key, so this build could not update it. Robin agreed to uninstall it; that dropped only PhotoXT's rebuildable media index. Debug builds are now signed with the laptop's Android Studio key, and every later build in this session installed over the last one (memory.md).
- No library outside §5.1 was added: Telephoto, Media3, ExifInterface, Maps Compose, Coil's GIF module and room-testing are all listed.
- The rest are in [memory.md](../memory.md): overlays, the hero flight, viewer order, zoom and double-tap, display modes, formats, player, resume, details sheet.

## Done
- `feature/viewer`: `ViewerScreen` with a `HorizontalPager` over the grid's items (keys by id, one page either side), chrome (back, date, display mode, zoom lock, overflow: slideshow, compare, open with, set as, copy), quick actions, `ImagePage` (Telephoto with sub-sampling, `CycleFitFillPixels` double-tap, two-finger rotation, zoom lock), `VideoPage` (poster and play), motion photo playback, `DetailsSheet` with `MiniMap`, `CompareScreen` (side by side or split slider, zoom and pan in sync), slideshow (interval, fade, slide or Ken Burns, shuffle, loop, music from a picked audio file), and the 360° view (`PanoramaRenderer`, drag to look, pinch to zoom).
- `feature/player`: `PlayerScreen` and `PlayerViewModel` on ExoPlayer and Media3 UI-Compose; swipe to seek, left/right swipes for brightness and volume, double-tap ±10 s, pinch zoom; speed 0.25–4×, aspect Fit, Fill, Stretch, 16:9, 4:3; loop, rotation lock, audio tracks, subtitles; picture-in-picture; `MediaSession`; "Resumed at …" with Start over.
- `core/media`: `ImageTraitsReader` (XMP motion photo and photo sphere parsing, Samsung trailer, Ultra HDR probe), `MediaDetails` (EXIF, video metadata, location with `ACCESS_MEDIA_LOCATION`), `SubtitleFinder`, `PlaybackPositions` with `resumePosition`, Share moved here from feature/timeline.
- `core/database`: Room v2 with `playback_position`, auto-migration 1→2 and its exported schema; viewer queries over the same order as the grid (`MediaListQuery`), item lookup and position of an item.
- `core/ui`: `MediaHero` and `HeroTiles` (the flight between tile and viewer, tile hidden in flight, reveal on close), grid hooks for it, `ImmersiveEffect` and `HdrWindowEffect`.
- `feature/timeline`: opens the viewer from any grid at the tapped position; Compare action when two items are selected.
- `app`: overlay back stack above the tabs (`Overlays.kt`), the hero state, Coil's animated decoder, PiP and config-change handling in the manifest, the Maps key placeholder; the Baseline Profile regenerated on the phone with a journey that now opens and zooms a photo (`app/src/main/baseline-prof.txt`, 27,452 lines, from 23,298).
- `core/designsystem`: 25 new Material Symbols Rounded icons.
- `baselineprofile`: `ZoomBenchmark` (opens the newest photo of an album, double-taps to Fill and 1:1, pans, zooms back) with a finger-timed double tap; the profile generator runs the same viewer journey.
- Fixed after the first phone run (below): the pager no longer reads Paging items before the first page loads; the viewer starts in its opening phase, so nothing of it shows before the flight; the system bars follow the viewer's bars only; double-tap and zoom lock measure the photo's own size (`unscaledContentSize`).
- Tests (61 in total, 43 before): `ImageTraitsTest` (8: XMP packet, Motion Photo video at the end and before a gain map, Pixel MicroVideo, Samsung trailer, photo sphere crop, subtitle names and languages, ISO 6709 video locations), `PlaybackPositionsTest`, `MigrationTest` (1→2 keeps the index; resume points go with their item), two new `TimelineQueryTest` cases (viewer order and positions; resume points), `DisplayModesTest` (4: mode bounds, the double-tap cycle, small photos skip 1:1, slideshow order), `TimelineCellTest` (sideways photos), `PlayerTest`, and `ShareTest` in its new home.

## Checks run
- The CI command in two Gradle runs, as in Phase 1: `spotlessCheck detekt lintDebug`, then `testDebugUnitTest --rerun assembleDebug :baselineprofile:assemble`. Both green: 61 tests passed, no Lint or detekt findings.

## On the phone (Galaxy A15 5G, SM-A156E, One UI 8 / Android 16, 90 Hz, wireless adb through WPSC)
- Test files, generated on the laptop into `build/cowork/p2/assets` (ignored by git, under the `.nomedia` folder) and pushed to `Pictures/PhotoXT test`: a 200 MP JPEG (16,320 × 12,240, 28 MB, with a 64 px grid, labels and zone plates for judging 1:1), a 360° photo sphere with `GPano` XMP, an animated GIF and WebP, a Google-format and a Samsung-format motion photo, an Ultra HDR sample (with gain map), and a 60 s 1080p video with its time burnt in plus a matching `.srt`. Each was checked against its format first (EXIF date and GPS, XMP, `SEFT` trailer, `ftyp` at the video offset). MediaStore listed all nine, the `.srt` as a subtitle.
- First open of the 200 MP photo crashed: the pager asked Paging for page 0 before the first page had loaded (`IndexOutOfBoundsException` in `PageStore`). Fixed with a bounds-checked read; no crash since.
- The debug build is no measure of smoothness on this phone: opening a photo ran at about 150 ms a frame, a third of it Compose's accessibility semantics, which stay on because Tasker and AutoInput are accessibility services here. Smoothness was measured on the release-like `benchmarkRelease` build with the Baseline Profile, as in Phase 1.
- Opening: on the first frames the viewer's bars showed over black, then hid for the flight and came back. After the fix, a 30 fps recording shows the photo growing out of its tile and the bars fading in once. Frame stats while opening: 114 frames, 8 janky (7%).
- Swipe down: the photo follows the finger and shrinks, the grid fades in underneath, and the photo lands in its own tile, which then shows again. 78 frames, 1 janky (1.3%).
- Double tap on the 200 MP photo first went Fit → Fill → 0.55× → 1:1 → 2.9×: each tap stepped up from the current zoom, because the photo's size was read from its fitted bounds converted at the current zoom. After the fix: Fit → Fill → 1:1 → Fit, and at 1:1 the 64 px grid shows as single-pixel lines 64 px apart, sharp.
- `ZoomBenchmark`, 5 runs of about 330 frames each (double-tap to Fill and to 1:1, three pans at 1:1, back to Fit). Frame time P50 / P90 / P95 / P99: 9.6 / 14.4 / 17.0 / 23.9 ms; overrun 0.5 / 6.9 / 9.7 / 16.6 ms (Phase 1 grid scrolling: −0.2 / 5.8 / 9.6 / 22.1). In the traces 9–22% of frames miss the app deadline, mostly while a 2,040 × 1,530 tile uploads on the render thread (10–18 ms each). Android Studio was mirroring the phone during the runs (the benchmark's warning was suppressed). Hardware-bitmap tiles measured no better (overrun 1.0 / 8.0 / 12.5 / 24.6), so tiles stay software bitmaps.
- Formats: both motion photos play their video from the Motion button; GIF and WebP animate; the 360° view renders the sphere and turns with a drag; with the Ultra HDR photo on screen the window's colour mode is `COLOR_MODE_HDR` (this panel has no HDR output, so it looks the same).
- Details sheet: date, 16320 × 12240 (199.8 MP) · 28 MB, camera, path, and after media-location access (One UI granted it without a dialog) 15.49890, 73.82780 with Open in Maps; no map without a key.
- Compare showed both photos side by side and with the split slider; the slideshow moved on through the album; a playing video went into a picture-in-picture window on Home.
- Video: subtitles showed ("Subtitle cue 3 (0:10)"). Left at 0:11, reopened: "Resumed at 0:11", and the media session reported 11.5 s two seconds later.
- 200% text: the viewer's bars, the details sheet and the player controls hold (the player title ends in "…"). A font-size change recreates the activity; the viewer comes back on the same photo, the player comes back paused.
- Logging (§7): debug build, a viewer journey (zoom, details, motion photo, 360°, video). 1,663 lines came from PhotoXT's process; none contains a file name, path, content URI or coordinates. No StrictMode report has a PhotoXT frame; three are leaked file descriptors in Telephoto's `UriImageSource`.
- Afterwards: the debug build is on the phone, the benchmark test app is removed, the test files and their MediaStore rows are deleted (9 rows → 0), Stay awake is off, the font size is back at Robin's 0.8, and the helper files are gone from `/data/local/tmp`.

## Phase 2 "Done when"
- A 200 MP image zooms smoothly to 1:1: met. Two double taps reach 1:1, sharp at single-pixel level, and `ZoomBenchmark` puts zoom and pan on par with Phase 1's accepted grid scrolling. The stricter §5.8 budget (under 1% janky frames) is Phase 15's; tile uploads are the main gap.
- Double-tap cycles through the modes: met, Fit → Fill → 1:1 → Fit on the phone (for a photo where 1:1 is smaller than Fill, 1:1 comes second; the cycle always zooms in until it resets). `DisplayModesTest` covers the order.
- Swipe-down closes the viewer with a shared-element transition: met, recorded (above).
- A video resumes where it was left: met (above), plus `PlaybackPositionsTest`, `MigrationTest` and the resume-point query test.

## §7 Definition of done
- Build and tests: green locally (above); GitHub Actions runs once this commit is pushed. No Lint or detekt warnings.
- Offline: nothing in Phase 2 uses the network. The Maps library adds the INTERNET permission, used only for map tiles once a key is set; without a key no map loads. The Wi-Fi-off check can't run over wireless adb (turning Wi-Fi off cuts the connection).
- Accessibility: every new icon button has a label from strings.xml; a page reads as "Photo, <date>"; 200% text holds on the phone (above). Not tried with TalkBack on the phone, which this session cannot drive.
- Logging: above.
- Records: [memory.md](../memory.md), this log and the README status line.

## Skipped, and when to add
- Editing a photo's date and place from the details sheet: Phase 5, with write access to device files.
- Favorite, Pin, Bookmark, Delete, Edit and Cast in the viewer: Phases 5–7 and 10.
- Sharing status, tags and OCR text in the details sheet: with their phases (11, 13).
- A-B repeat, frame stepping, frame capture and background audio in the player: not asked for in Phase 2; later with video tools (Phase 9).
- Keeping the display mode, zoom lock and slideshow options across launches: Settings (Phase 6); they last for the app session now.
- Mouse wheel, Ctrl+scroll and keyboard in the viewer: the large-screen and DeX pass (Phase 14).
- Compare decodes each photo once at up to 4096 px: sharp to about 1:1 for 16 MP shots; tiled decoding as in the viewer if bigger photos need comparing up close (`ponytail:` in CompareScreen).
- Smaller tiles or paced uploads for very large photos: Phase 15's performance pass (`ponytail:` in ImagePage).
- Robolectric screenshots of the viewer and player: not made; their layout at 200% text was checked on the phone instead.

## Problems met
- Maven Central rate-limited (HTTP 429) the cloud's Gradle and Robolectric downloads; a Google mirror init script in the cloud workspace only (not in the repo) got round it.
- A `SurfaceView` ignores Compose's layer scaling, so the player zooms by laying the video out larger instead.
- Media3's `@UnstableApi` opt-in through Kotlin's `@OptIn` raised a warning (warnings are errors); `androidx.annotation.OptIn` is the one Media3 expects.
- Subtitle files with upper-case extensions (`clip.en.VTT`) were matched wrongly; fixed and covered by `ImageTraitsTest`.
- Gradle 9 fails a test task that has sources but finds no tests; `feature/player` got `PlayerTest`.
- detekt flagged long composables, return counts and magic numbers once everything compiled; composables were split and constants named. `ReturnCount` now ignores guard clauses.
- The phone's Phase 1 build was signed with another cloud session's debug key, so this one could not update it (above).
- The session's file transfer altered JPEGs on the way to the laptop (different bytes); binaries now travel gzipped or in `.part` files, checked by SHA-256 (memory.md).
- The phone dropped off wireless adb mid-session until Robin turned Wireless debugging back on; adb's transport ids changed with each reconnect, so scripts now use the device serial.
- The first phone run found the pager crash, the opening flash and the double-tap levels (above); unit tests had covered the zoom order but not how the photo's size was read.
- Macrobenchmark's `StartupMode.COLD` kills the app after the setup block, so the first `ZoomBenchmark` run tapped the home screen instead (it hit the clock widget, then Back). The benchmark now kills the app at the start of setup and uses no startup mode.
- Android Studio's device mirroring makes Macrobenchmark refuse to run; the runs passed `androidx.benchmark.suppressErrors=DEVICE-MIRRORING`.

## Next
- Push this commit (`git push`) so CI runs on it at GitHub.
- Optional: a Maps API key for the mini map (README).
- For benchmarks, stop Android Studio's device mirroring first (Running Devices tab).
- `build/cowork` (ignored by git) holds the test APKs, test files, screenshots of the phone (`p2/shots`, some show the phone's own library) and transfer parts, plus a copy of the laptop's debug keystore (`p2/laptop-debug.keystore`); delete it when no longer needed.
- Then Phase 3 (Google account and Drive albums).
