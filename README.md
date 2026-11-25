# PhotosXT

**PhotoXT** is a native Android gallery (Kotlin + Jetpack Compose) built to beat Samsung Gallery. It adds selected Google Drive folders as streamed albums with on-device thumbnails and a 30-day download cache. It also covers editing, collages, casting to TVs, and albums that are private by default and can be shared or made public. **PhotoXTPro** is the same app plus public media boards (image, video and media boards that other users post to).

**Status (2026-11-25):** Phase 8 (Collage studio) of PhotoXT is done, its on-phone checks included. Select 2–20 photos, then More → *Make collage*. The studio offers 100+ grid layouts, a smart layout that fits the photos' shapes and keeps faces in, freeform, stitch, shapes (heart, circle, hexagon, letters), Story 9:16 and before/after. The canvas takes print and custom sizes, spacing, margins, corners, borders and colour, gradient, blurred-photo, pattern or transparent backgrounds. Each photo can be swapped, panned, zoomed, turned, flipped or replaced, and given a look through the editor's pipeline. Text, stickers, frames, snapping guides, a layers panel and 100-step undo are there too, and drafts save as you go (Library → Collages). It exports JPEG, PNG or WebP up to 8K, by default into the first photo's album. *Delete sources* sends the photos to the Trash only after the collage is saved and checked, and Undo brings them back. On the Galaxy A15, nine 12.5 MP photos made a 4K collage in 3.2 s (the limit is 5 s), and in the app nine photos went to the Trash only after their collage was written. Undo brought them back byte for byte. The checks also fixed a Phase 1/5 bug: photos PhotoXT put in the trash stayed in their album until the app came back to the front. A look on every photo makes the export slow (26 s for nine); a lighter shader for looks is the follow-up. CI is green. Committed locally; CI runs on GitHub once it is pushed. Next step: Phase 9 (Video tools).

| File | What it is |
|---|---|
| [photoxt-build-prompt.md](photoxt-build-prompt.md) | Build prompt and spec for **PhotoXT** (no media boards). Give it to an AI coding agent and build one phase per session. |
| [photoxtpro-build-prompt.md](photoxtpro-build-prompt.md) | Build prompt and spec for **PhotoXTPro**: PhotoXT plus media boards. Same section and phase numbers. |
| [AGENTS.md](AGENTS.md) | Entry point for any AI agent working in this folder |
| [memory.md](memory.md) | Durable decisions and facts for this project |
| [.session/](.session/) | One log file per working session |
| `app/`, `core/`, `feature/`, `baselineprofile/`, `build-logic/` | The Android project (one Gradle build; open this folder in Android Studio) |
| [models/](models/README.md) | The eraser's DeepFill v2 model, built for LiteRT, and the script that builds it |
| [.github/workflows/ci.yml](.github/workflows/ci.yml) | CI: the same checks as below, on every push to `main` |

## Build and check

Needs JDK 17 or newer and the Android SDK (Android Studio has both). One command runs every check CI runs:

```text
./gradlew spotlessCheck detekt lintDebug testDebugUnitTest assembleDebug :baselineprofile:assemble
```

On Windows use `gradlew.bat`. `./gradlew spotlessApply` fixes formatting, `./gradlew recordRoborazziDebug` re-records the screenshot tests after an intended UI change, and `./gradlew installDebug` puts the app on a connected phone.

The editor's export check runs on a phone: `./gradlew :core:render:connectedDebugAndroidTest` renders a 50 MP photo and checks that the export matches the preview. The eraser's check also runs on a phone: `./gradlew :core:ml:connectedDebugAndroidTest` downloads LaMa, MI-GAN and DeepFill v2 (129 MB) and has each fill the same hole.

### Google sign-in (Drive albums)

Drive albums need Google sign-in, which needs the Web client id of the PhotoXT Google Cloud project (Google Auth Platform → Clients). Keep it out of git: put `PHOTOXT_GOOGLE_WEB_CLIENT_ID=<id>.apps.googleusercontent.com` in `~/.gradle/gradle.properties` (on Windows `%USERPROFILE%\.gradle\gradle.properties`; robin-laptop has it), or set an environment variable of that name, then rebuild. Without it the app builds and runs, and *Add Google account* says sign-in isn't set up. The project's Android client must also know the signing key: it has the SHA-1 of robin-laptop's Android Studio debug key, so builds signed with another key need their own Android client. While the project is in Testing, only its listed test users can sign in, and Google ends their Drive access after 7 days; PhotoXT then shows *Reconnect*.

### Maps API key (optional)

The viewer's details sheet draws a small map of where a photo was taken. That needs a Google Maps SDK for Android key. Without one the app still builds and runs, and the sheet shows the coordinates with an *Open in Maps* button instead. To add a key, keep it out of git: put `PHOTOXT_MAPS_API_KEY=<your key>` in `~/.gradle/gradle.properties` (on Windows `%USERPROFILE%\.gradle\gradle.properties`), or set an environment variable of that name, then rebuild. Restrict the key in Google Cloud to Android apps with package `com.photoxt.app` and your signing certificate's SHA-1.

Shared workspace rules live in the Projects hub: [README](../../../README.md) · [AGENTS](../../../AGENTS.md) · [conventions](../../../conventions.md) · [skills](../../../skills.md). Parent project: [Android](../README.md).
