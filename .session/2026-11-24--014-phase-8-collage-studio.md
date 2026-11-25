# 2026-11-24 — 014 — Phase 8: Collage studio

- **Tool:** Claude (Cowork), a cloud session linked to robin-laptop, on 2026-11-24 and 2026-11-25. Built and tested in the cloud workspace (Linux, JDK 21, Android SDK 37) on `main` at 2afe88a, which came over as a git bundle (`build/cowork/p8/start.bundle`).
- **Goal:** Robin asked: "build phase 8". That is the collage studio (spec §4.11, §6 Phase 8). Done when:
  - a 9-photo collage exports at 4K in under 5 s on a mid-range phone;
  - with *delete sources* on, the sources reach the Trash only after the export verifies;
  - Undo restores them.

## Decisions (made, for Robin to confirm)
- **The export is a transfer job** (`COLLAGE`): render, write, decode back, place with MD5. The *delete sources* TRASH jobs don't exist until the collage job is marked DONE, because both are written in one Room transaction. So a failed render, a corrupt file or a failed check can never bin a photo.
- **Undo** turns the trash jobs into RESTORE jobs and keeps the collage. The snackbar offers it for about 10 s; after that, Library → Transfers still has *Undo* on the batch.
- **Which photos:** *Make collage* takes the selected photos oldest first. Videos are left out, and 2–20 photos are needed. It isn't offered in the Hidden list, where a collage would show hidden photos outside it.
- ***Delete sources* bins only the photos the collage shows.** A before/after draft keeps its other photos, and those are never binned.
- **Stitch** keeps every photo whole: its cells are worked out from the photos' shapes each time it is drawn, so a swap or a new photo can't crop one. It has no dividers, and it exports at the narrowest photo's own width rather than the 8K cap.
- **Sizes:** HD, 2K, 4K (the default) and 8K. 8K is capped at 33 MP, so a square comes out at 5,760 × 5,760. If memory is short, the export shrinks to fit rather than fail.
- **Shapes** clip the whole collage to a heart, circle, hexagon or letters. **Before/after** uses the first two photos, with labels you can edit.
- **Drafts:** saved to Room as you work (v8, table `collage_project`), each with a picture, and listed in Library → Collages to open again or delete.
- The studio's sliders and chips are copies of the editor's (marked `ponytail:`); they move to the design system when a third screen needs them. There are 8 new icons.

## Built
- `core/model` `Collage.kt`: the project (layout tree, cells, free placements, canvas, layers, output) and `TransferOp.COLLAGE`.
- `core/render`:
  - `CollageLayouts`: the generated templates (100+ across 2–20 photos), smart layout (linear partition, face-aware focus), stitch and scatter.
  - `CollageGeometry`: cell rectangles, dividers and their drags, swaps, sizes.
  - `CollageRenderer`: backgrounds (colour, gradient, blurred photo, patterns, transparent), cells with corners, borders and flips, shapes, text, stickers and frames.
  - `CollageExport`: parallel region decodes, a memory fit, JPEG/PNG/WebP, and the decode check.
  - `LookPass`: per-cell looks through the editor's shader.
  - `CollageJson`.
- `core/database`: `collage_project` and `CollageDao` (Room v8, auto-migration 7→8).
- `core/transfer` `Collages.kt`: `CollageOps` (default album, phone/Drive counts, export) and the COLLAGE job, plus its follow-up TRASH jobs, Undo, and the Drive-scope wait for the trash accounts.
- `feature/collage`, a new module:
  - The studio: canvas gestures (pan, pinch, twist, swap by drag, divider drags with snapping guides) and TalkBack actions.
  - Panels: Layout, Canvas, Photo, Text, Stickers, Frame, Layers.
  - Undo/redo with 100 steps; the export dialog; the photo picker for *Replace*; the drafts screen.
- `app`: `CollageKey` overlay, Library → Collages, and *Make collage* in the selection's More menu. The snackbar reads "Collage saved to …; its N photos moved to the trash", with Undo.
- Tests:
  - `CollageLayoutTest` (12) and `CollageRenderTest` (4), in core/render.
  - `CollageJobsTest` (6): a failed render or a corrupt file bins nothing, Undo restores, and the Drive scope wait.
  - `MigrationTest` 7→8.
  - `CollageEditsTest` (3, feature/collage): a gesture is one undo step; a stitch keeps photos whole after a swap; before/after labels.
  - `CollageScreenshotTest` (4 goldens: Layout and Canvas panels at 100% and 200% text).
  - `CollageExportSpeedTest` (core/render androidTest, for the phone).

## Found and fixed on the way
- **A stitch cropped photos after a swap:** its cell sizes were computed from the photo order before the swap. Found by `CollageEditsTest`. The geometry now lays a stitch out from its photos each time it is drawn.
- **The canvas dropped every gesture after its first move:** the gesture handler restarted whenever the studio redrew. It now keeps the same actions.
- **Wrong download progress** when a collage mixed phone and Drive photos.
- **On the A15: photos PhotoXT put in the trash stayed in the album until the app came back to the front.** This is a Phase 1/5 bug that every in-app Delete had. MediaStore reports a trash as an update, and the quick index pass only read updated items, which a trashed item no longer is. The quick pass now also asks MediaStore which items were trashed since its last pass (`MediaStoreReader.trashedSince`), with a test.

## Checks run
- The CI command plus the phone check's build: `spotlessCheck detekt lintDebug testDebugUnitTest assembleDebug :baselineprofile:assemble :core:render:assembleDebugAndroidTest`. Green: 263 tests (232 before), no Lint or detekt findings.
- The first full run found 49 detekt findings and 2 Lint errors in the new code (a count that needed a plural, and state read in a test's composition); all were fixed. `TooManyFunctions` now ignores Hilt `@Module`s (`DatabaseModule` gained a DAO).

## Phone check (2026-11-25, Galaxy A15, Android 16, through WPSC)
- **`CollageExportSpeedTest`:** nine 12.5 MP photos with camera-like grain (4.8 MB each) exported to a 3,840 × 3,840 JPEG in **3.2 s**, checked by decoding it back. The same collage with a look on every photo took 26 s; the editor's whole shader runs once per cell. That is recorded as a gap.
- **In PhotoXT** (debug build, installed over the old one, data kept), with nine 4.6 MB test photos in `Pictures/PhotoXT p8 test`:
  - Select all → More → *Make collage*. The studio opened with 18 layouts for nine photos, oldest first. TalkBack names were present ("Photo 1 of 9", "Divider between photos", "Layout 1 of 18", Undo and Redo disabled).
  - Save with *delete sources* ticked ("9 on this phone · 0 in Google Drive"). The collage was rendered in **2.3 s** and placed, and only then did MediaStore's "move 9 photos to bin?" screen open. After *Allow*, the nine were trashed, the collage stayed, and the snackbar offered Undo.
  - Undo (from Transfers, after the snackbar had gone) opened "move 9 photos out of bin?". After *Allow*, all nine were back, byte for byte the same (MD5), with the collage still there.
  - Library → Collages listed the draft: "9 photos · Saved · 4 minutes ago".
  - The trash fix is installed on the phone (the build at 16:36). Its own run there (a collage with *delete sources* on, the photos leaving the album at once, then Undo) waited for the phone to be unlocked: it had locked during the build.
- 200% text was checked by the screenshot tests, not on the phone; that would need changing Robin's font size there.

## Not done
- The 26 s export with a look on every photo. A lighter shader for looks alone would fix it; it is a follow-up, not part of this phase's checks.
- Drive photos in a collage weren't tried on the phone; the Drive scope wait is covered by `CollageJobsTest`.

## Committing
- One commit in the cloud clone. It came to the laptop as `build/cowork/p8/phase8.bundle` and was fast-forwarded onto `main` there with Windows git through WPSC. Not pushed. Robin's own change to `photoxt-build-prompt.md` (its tables reformatted) was left as it was, uncommitted.
- The phone's test photos (`Pictures/PhotoXT p8 test`) and the helper files in `build/cowork/p8/` stay until the trash fix's run on the phone is done.

## Problems met
- The cloud workspace restarted overnight, and swap had to be switched back on.
- Files sent to the laptop through the session's file store can arrive a version late, or with a C2PA (Content Credentials) block added to JPEGs; pixels and EXIF were untouched. Check what landed before using it.
- Wireless adb pulled the 3.3 MB collage at 0.1 MB/s.
