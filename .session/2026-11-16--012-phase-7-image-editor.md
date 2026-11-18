# 2026-11-16 — 012 — PhotoXT Phase 7: image editor

- **Tool:** Claude (Cowork), cloud session linked to robin-laptop, 2026-11-16 to 2026-11-18. Built and tested in the cloud workspace (Linux, JDK 21, Android SDK 37) on `main` at 273b5ed; the laptop reached through WPSC and the Cowork device bridge.
- **Goal:** Robin asked to "build Phase 7" (spec §6, Phase 7, and §4.10): the recipe model, GPU pipeline and tiled export; the tools in the spec's order (transform → light and colour → filters and LUTs → text, draw, stickers → redact → selective masks → cut-out → object eraser); save and revert; copy/paste edits and batch apply. Done when a 50 MP export matches its preview, Revert restores a bit-identical original, and an edited Drive photo shows as a new version in Drive on the web.

## Decisions (asked)
- HEIC and AVIF export: add `androidx.heifwriter` 1.1.0 (not in §5.1).
- Object eraser: both models, switchable in Settings: MI-GAN (default, 16 MB, fast) and LaMa (183 MB, best quality).

## Decisions (made, for Robin to confirm)
- LiteRT runs through Google Play services (`play-services-tflite-java`/`-gpu` 16.5.0), so the APK carries no interpreter. The phone needs Play services, as it already does for ML Kit.
- The only LiteRT build of LaMa found sits on a personal Hugging Face account; it is pinned by commit and SHA-256, as MI-GAN is (litert-community). Both should move to PhotoXT's own storage before release.
- *Save* writes over the photo in its own format, size and metadata at quality 95; formats PhotoXT can't write (GIF, RAW and others) offer *Save as copy* only. *Save* on a phone photo asks for `ACCESS_MEDIA_LOCATION` first, so the location is never lost.
- Paste always takes the look (light, colour, detail, filter, retouch) and takes geometry, overlays and the frame only when ticked; red-eye fixes are never pasted; each photo's own subject and faces are found again.
- The editor is always dark, whatever the app's theme, as photo editors usually are, so colours are judged against the same surround.
- *Undo* of a save in Transfers restores the previous edit or the original.
- A kept original goes 31 days after its photo left PhotoXT, a day longer than the trash keeps it; a photo that comes back keeps it.
- Crop's *Custom* ratio is typed as width:height (`3:2`, `3x2`, `1.91/1`), from 1:10 to 10:1.
- Revert on Drive deletes the kept revision once the original's bytes are back as the current version and checked: Drive never lets a revision stop being kept forever, and a kept duplicate would cost storage on every save and revert.
- During the phone checks PhotoXT asked for notifications, and I allowed it so its transfer notifications could be seen; the setting is yours to change.

## Built
- **Recipe and pipeline:** `EditRecipe` (core/model `Edit.kt`, JSON version 1), one value per tool, rendered in a fixed order whatever order the tools were used in. One GLES 3.00 shader renders the preview and every export tile; neighbourhood effects read fixed 1,024 px guides of the whole photo, so the preview, the export and the tiles agree.
- **Export (`Save as copy`):** JPEG, PNG, WebP, HEIC, AVIF or the same format; quality; file-size targets (searched quality, then size); resize by long edge or percentage; keep or strip metadata. JPEG and PNG stream in 2,048 × 256 strips through PhotoXT's own writers, so a 200 MP photo never sits whole in memory; the other formats need one memory-checked bitmap.
- **Transform:** crop (free, original, 1:1, 4:5, 3:4, 9:16, 16:9, custom), rotate, flip both ways, straighten (cropped to fill), keystone both ways and four-corner perspective.
- **Light, colour and detail:** exposure, brightness, contrast, highlights, shadows, whites, blacks and *Auto*; temperature, tint, hue, saturation, vibrance, an HSL mix for eight colours and RGB/red/green/blue curves; sharpen, clarity, dehaze, vignette, grain, fade.
- **Filters:** built-in looks with intensity, `.cube` LUT import, saved presets.
- **Text, draw, stickers, frame, watermark:** text layers (font, size, colour, bold, outline, shadow, background, alignment; moved, turned and sized on the photo); pen, marker, highlighter, eraser and shapes (rectangle, ellipse, line, arrow); emoji and picture stickers; solid, Polaroid and rounded frames; a text or logo watermark in five places.
- **Redact:** blur, pixelate or solid boxes and ellipses, drawn or found: faces, text and number plates (ML Kit, Latin and Devanagari).
- **Selective:** brush, linear, radial, subject and background masks with feather and invert, each with its own exposure, contrast, highlights, shadows, saturation, temperature, tint and clarity.
- **Retouch, cut-out, eraser:** red-eye, skin smoothing on found faces, background blur; the subject on a removed, coloured, blurred or picture background, or lifted out to share; the object eraser on a brushed area with MI-GAN or LaMa (GPU first, then CPU), the model downloaded on first use (resumable, SHA-256 checked) and deletable in Settings.
- **Editing:** undo and redo, a history list to jump to any of 100 steps, *Reset* per tool, Android 17's eye dropper for colours (picked from the photo on older versions).
- **Save and revert (Room v7, auto-migration 6→7):** a phone photo's original is copied into app storage and read back before it is first written over; a Drive photo's head revision is kept forever and the edit goes up as its next revision. Revert writes the kept bytes back; on Drive as a new revision, checked, after which the kept revision is deleted. Edits run in the transfer queue as Edit, Edit copy and Revert, with resume and Undo.
- **Copy, paste, batch:** *Copy edits*, *Paste edits* and *Revert edits* in the viewer; *Paste edits* on a grid selection applies one edit to many photos as one batch.
- **Fixed on the way:** phone photos were read with their GPS position stripped unless read through `MediaStore.setRequireOriginal`, which is why Phase 5's copies and moves of phone photos lost their location. Reads now go through it whenever `ACCESS_MEDIA_LOCATION` is granted.

## Tests
- `RenderMathTest` (14: geometry, EXIF orientation, turns and flips, crop and scale, straightening without empty corners, keystone, frame, inverse mapping, centred crops, curves without overshoot, `.cube` parsing, built-in looks, recipe JSON, paste merging), `StripWritersTest` (3: JPEG decodes to the same picture, PNG is lossless, transparency over white).
- `EditJobsTest` (8: a phone photo keeps its original and reverts bit for bit; a Drive photo gets a new revision with its original kept forever; *Save as copy* leaves the original alone; Undo; paste over each photo's own edit; nothing overwritten without the unredacted original; pasted skin smoothing finds each photo's own faces; a gone photo's original goes after a month unless it comes back).
- `EditorHistoryTest` (4), `CropRatioTest` (2), `PlatesTest` (2), `MigrationTest` 6→7, screenshot tests of the editor bare and with the Light panel at 100% and 200% text.
- `ShaderCompilesTest` (1): the shader through the Khronos reference compiler (`glslangValidator`) as GLSL ES 3.00; CI installs `glslang-tools`, and without it the test is skipped.
- After the phone checks: the Drive revert test runs against a fake Drive that refuses to un-keep a revision, as Drive does, and checks the kept revision is deleted; the phone save test checks nothing is rendered before MediaStore's consent.
- `ExportMatchesPreviewTest` (core/render androidTest, for the phone): an 8,160 × 6,120 photo exported and compared with its preview scaled up, mean difference ≤ 3 and 99th percentile ≤ 24 levels.

## Problems met
- Gradle 9 fails a test task that discovers no tests, and Hilt generates test sources in every Hilt module, so `core/ml` and `feature/editor` needed a test each before CI could pass.
- The full CI ran the cloud workspace out of memory until it had 6 GB of swap.
- Lint (warnings as errors) wanted the KTX forms throughout (`createBitmap`, `scale`, `withTranslation`, `bitmap[x, y]`) and `LocalResources`; detekt found a few hundred issues in the new code, fixed by splitting files and functions, with a handful of reasoned `@Suppress` and one rule change (simple `when` entries don't count towards complexity).
- The first screenshots showed *Save* cut off at 200% text, tool labels clipped, and white bars in the light theme: the editor is now always dark, its top bar scrolls with *Save* measured first, and the tool row scrolls with a minimum width per tool.
- From the cloud session, copying the WPSC credentials elsewhere was refused; running the staged issuer in place works.
- The phone dropped off wireless adb on 2026-11-17 and again on 2026-11-18, two minutes after it came back: Wireless debugging switches itself off when the phone's Wi-Fi reconnects.
- Found on the phone and fixed (each now has a test):
  - The shader didn't compile on the A15's Mali driver: a variable was named `half`, a reserved word in GLSL ES 3.00, so the editor would have drawn nothing. CI has no GPU to catch it; `ShaderCompilesTest` now does.
  - The 50 MP test method returned a value (`= runBlocking { }`), which JUnit 4 rejects ("should be void"): now `runBlocking<Unit>`.
  - Drive refuses to un-keep a revision (HTTP 400, `illegalKeepForeverModification`), so Revert left the original kept forever. Revert now deletes the kept revision after the original's bytes are back and checked, and drops the edit row before cleaning up, so a revert stopped half-way finds nothing left to do.
  - *Paste edits* on two phone photos asked MediaStore's consent twice, one screen per photo, each after its render. A phone save now checks write access before rendering, so a batch waits together and gets one screen ("Allow PhotoXT to modify 2 photos?").
  - StrictMode: the editor's and the eraser models' setup touched the disk on the main thread (`cacheDir`, `noBackupFilesDir`, a new `OkHttpClient`), and so did the model check after a download; now lazy or on the IO dispatcher. Samsung's `Toast.show` reads the disk too (`PackageManager.isSpeg`); not ours.
- Seen and left alone: the first transfer's notification request opened on top of MediaStore's consent screen, and the jobs waited until both were answered. MediaStore guesses *Date taken* for an EXIF time without a zone from the file's modified time, so the test photos showed today's time and moved after a save; camera photos carry the zone.

## Checks run
- The CI command, `spotlessCheck detekt lintDebug testDebugUnitTest assembleDebug :baselineprofile:assemble :core:render:assembleDebugAndroidTest`: green, 230 tests (189 before the phase), no Lint or detekt findings, after the phone fixes too. To stay under detekt's function limits, Drive's revision calls moved into their own Retrofit interface (`DriveRevisionApi`), and `DeviceStore` carries a reasoned `@Suppress`.
- The phone runs the build with every fix from the checks; the last tidy-up (the lazy setup, the model check on the IO dispatcher, `DriveRevisionApi`) changes no behaviour and didn't reach it, because the phone dropped off adb again. That build is `build/cowork/p7/app-final.apk` on the laptop.
- Screenshot goldens added for the editor: `editor`, `editor_200`, `editor_light`, `editor_light_200`; no existing golden changed.
- Logging: every new line goes through `PxtLog`, which masks tokens, emails, URIs, paths, media file names and coordinates; the editor and its jobs log no names, places or accounts.

## Phone checks (2026-11-18, Galaxy A15, Android 16, debug build)
Test data: four generated 12 MP photos with EXIF and a GPS position (`p7a`–`p7d`) in `Pictures/PhotoXT p7 test`, and `p7c`, `p7d` in a My Drive folder "PhotoXT p7 test" put there through Drive for desktop. Helpers in `build/cowork/p7` (`x.ps1`, `p7.sh`, `scan.sh`, `a11y.ps1`).
- **Upgrade:** installed over the Phase 6 build of 2026-11-16; Room migrated 6→7, with no crash or migration error in the log.
- **50 MP export (done):** `ExportMatchesPreviewTest` passed in 54 s. An 8,160 × 6,120 photo, exported at 7,646 × 5,320 after its crop and straightening, against its 1,024 × 712 preview: mean difference 0.48 and 99th percentile 4 levels (limits 3 and 24). Side by side they look the same.
- **Bit-identical revert (done):** p7a saved with the Warm look: new bytes (321,518 → 589,544), same MediaStore id, EXIF date, camera and GPS kept, and the original kept in app storage with its own MD5. *Revert to original* brought back MD5 `1ffaa466…` exactly and removed the kept copy.
- **Drive version (done):** p7c saved with Mono: Drive's revision list showed the new version with the original kept forever, and the copy Drive for desktop synced was the Mono picture with its EXIF and GPS. *Revert* brought the original's MD5 back as the newest version, but the original stayed kept forever (the 400 above). After the fix, p7d: saved with Noir (new version, original kept), then reverted: the newest version is the original's bytes (319,244), the kept revision is gone, and nothing is kept forever.
- **Batch paste:** Warm pasted onto p7b and p7c: one consent screen after the fix (two before it), both saved, then both reverted bit for bit.
- **Object eraser:** MI-GAN downloaded (16 MB) and erased a block from p7d's preview on the phone's GPU; the edit was discarded.
- **Log scan:** 3,332 lines from PhotoXT's uid since its first launch that day: no file or folder names, coordinates, account, tokens, paths, content URIs, Drive ids or camera model.
- **TalkBack names:** every clickable or checkable node in the editor, its 15 tool panels and the *Save as copy* dialog has a name, its own or merged from its children. One chip cut off at the screen's edge had none until scrolled in.
- **200% text:** the editor with the Light panel, and the *Save as copy* dialog, fit with *Save* whole; chip rows scroll.
- **Tried later the same day (Robin asked):** see the next section; LaMa still to run.
- **Clean-up:** the phone test folder and its MediaStore rows deleted, the Drive album removed from PhotoXT and the Drive folder binned (p7c's original stays kept forever inside it until Drive empties the bin after 30 days), the render test app uninstalled, `/data/local/tmp` cleared, text size back to 80%. Left on the phone: the MI-GAN model (Settings → Editor deletes it), PhotoXT's notification permission, and the edit clipboard.

## Follow-up on the phone (2026-11-18, afternoon)
Robin turned wireless adb back on and asked for the features not yet tried. Test photos, deleted afterwards: a drawn street scene with an English sign, a Devanagari sign and a Goa plate (`p7e`), and faces generated by StyleGAN, no real people: NVIDIA's 15-face sample (`p7f`) and one face from it enlarged (`p7g`).
- **Redact, automatic finding:** all 15 faces in `p7f`; in `p7e` the English sign, the Devanagari sign and the plate. The first run missed the Devanagari sign without a word, because Play services was still downloading that model. Fixed: a model still downloading now stops the step with "Google Play services is still getting this tool ready. Try again in a minute." (`ModelsDownloadingException`), rather than passing for nothing to hide. Redaction must not leave a sign showing quietly.
- **Skin smoothing:** smoothed the skin of the face in `p7g` and left eyes and brows sharp; only the face area changed.
- **Background blur and cut-out:** the subject model also downloaded on first use. The first try showed "That didn't work", which the same fix now words properly; after that, cut-out put a plain colour behind the subject with clean edges.
- **Pick a colour (Android 16 fallback):** *Pick a colour*, then a tap on the photo, took the hair's colour (87, 51, 25 against 88, 52, 26 under the tap) for the cut-out background. Only the Draw panel shows the "tap the photo" hint; Cut-out shows none.
- **HEIC and AVIF copies:** both saved (1,536 × 1,536 from a 3 × 3 grid of 512 px tiles, about 51 kB each, EXIF kept) and both open in PhotoXT and decode elsewhere. But their EXIF said 1 × 1, taken from the 1 × 1 JPEG the EXIF block is built in, so MediaStore and PhotoXT's Details showed "1 × 1". Fixed, with `MetadataTest`.
- **Not yet:** LaMa (a 183 MB download), because the phone was in use again; and the build with these fixes isn't on the phone, because adb over Wi-Fi had dropped to 0.4 MB/s and broke off. The fixes are covered by tests.

## Accessibility
- Every new control has a TalkBack name: the tool tabs, top-bar icons, sliders (named, with their value), chips and switches, the curve editor (named, with its point count and actions to lighten, darken, add contrast or straighten the curve), the preview, and the export dialog's choices.
- The editor holds at 200% text in the screenshot tests and on the phone. Checked through the UI tree, not with TalkBack running.
