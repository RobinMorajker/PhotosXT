# PhotosXT

**PhotoXT** is a native Android gallery (Kotlin + Jetpack Compose) built to beat Samsung Gallery. It adds selected Google Drive folders as streamed albums with on-device thumbnails and a 30-day download cache. It also covers editing, collages, casting to TVs, and albums that are private by default and can be shared or made public. **PhotoXTPro** is the same app plus public media boards (image, video and media boards that other users post to).

**Status (2026-11-05):** Phase 2 (Viewer and player) of PhotoXT is built and its checks pass locally (61 tests, no Lint or detekt findings). Tapping a photo opens it out of its tile into a viewer with zoom up to 4× beyond 1:1, double-tap Fit → Fill → 1:1, six display modes including Stretch, zoom lock, Compare and a details sheet; swipe down flies it back. Motion photos, Ultra HDR, GIF/WebP and 360° photos play, there is a slideshow, and videos open in a Media3 player with gestures, speed, picture-in-picture, subtitles and resume. All four "Done when" checks pass on a Galaxy A15: a 200 MP photo zooms to sharp 1:1 pixels, double-tap cycles Fit → Fill → 1:1, swipe-down flies the photo back into its tile, and a video resumes where it was left. Committed locally; CI runs on GitHub once it is pushed. Next step: Phase 3 (Google account and Drive albums).

| File | What it is |
|---|---|
| [photoxt-build-prompt.md](photoxt-build-prompt.md) | Build prompt and spec for **PhotoXT** (no media boards). Give it to an AI coding agent and build one phase per session. |
| [photoxtpro-build-prompt.md](photoxtpro-build-prompt.md) | Build prompt and spec for **PhotoXTPro**: PhotoXT plus media boards. Same section and phase numbers. |
| [AGENTS.md](AGENTS.md) | Entry point for any AI agent working in this folder |
| [memory.md](memory.md) | Durable decisions and facts for this project |
| [.session/](.session/) | One log file per working session |
| `app/`, `core/`, `feature/`, `baselineprofile/`, `build-logic/` | The Android project (one Gradle build; open this folder in Android Studio) |
| [.github/workflows/ci.yml](.github/workflows/ci.yml) | CI: the same checks as below, on every push to `main` |

## Build and check

Needs JDK 17 or newer and the Android SDK (Android Studio has both). One command runs every check CI runs:

```text
./gradlew spotlessCheck detekt lintDebug testDebugUnitTest assembleDebug :baselineprofile:assemble
```

On Windows use `gradlew.bat`. `./gradlew spotlessApply` fixes formatting, `./gradlew recordRoborazziDebug` re-records the screenshot tests after an intended UI change, and `./gradlew installDebug` puts the app on a connected phone.

### Maps API key (optional)

The viewer's details sheet draws a small map of where a photo was taken. That needs a Google Maps SDK for Android key. Without one the app still builds and runs, and the sheet shows the coordinates with an *Open in Maps* button instead. To add a key, keep it out of git: put `PHOTOXT_MAPS_API_KEY=<your key>` in `~/.gradle/gradle.properties` (on Windows `%USERPROFILE%\.gradle\gradle.properties`), or set an environment variable of that name, then rebuild. Restrict the key in Google Cloud to Android apps with package `com.photoxt.app` and your signing certificate's SHA-1.

Shared workspace rules live in the Projects hub: [README](../../../README.md) · [AGENTS](../../../AGENTS.md) · [conventions](../../../conventions.md) · [skills](../../../skills.md). Parent project: [Android](../README.md).
