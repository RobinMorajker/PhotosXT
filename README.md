# PhotosXT

**PhotoXT** is a native Android gallery (Kotlin + Jetpack Compose) built to beat Samsung Gallery. It adds selected Google Drive folders as streamed albums with on-device thumbnails and a 30-day download cache. It also covers editing, collages, casting to TVs, and albums that are private by default and can be shared or made public. **PhotoXTPro** is the same app plus public media boards (image, video and media boards that other users post to).

**Status (2026-11-06):** Phase 3 (Google account and Drive albums) of PhotoXT is built and its checks pass locally (81 tests, no Lint or detekt findings). You sign in with one or more Google accounts, browse Drive in the app (My Drive, shared with me, shared drives, starred, search) and add a folder as an album, with its subfolders as nested albums or merged into one. The scan keeps Drive's thumbnails on the phone with BlurHash placeholders; photos open from a checked download and videos stream through a cache. On a Galaxy A15 a 2,000-photo Drive folder was browsable 3.4 s after adding it (scanned in 10.6 s), its thumbnails showed in airplane mode, and a 1 GB video jumped to 7:10 after fetching about half of it. The Phase 3a spike's findings are in memory.md. Committed locally; CI runs on GitHub once it is pushed. Next step: Phase 4 (Sync and cache lifecycle).

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

### Google sign-in (Drive albums)

Drive albums need Google sign-in, which needs the Web client id of the PhotoXT Google Cloud project (Google Auth Platform → Clients). Keep it out of git: put `PHOTOXT_GOOGLE_WEB_CLIENT_ID=<id>.apps.googleusercontent.com` in `~/.gradle/gradle.properties` (on Windows `%USERPROFILE%\.gradle\gradle.properties`; robin-laptop has it), or set an environment variable of that name, then rebuild. Without it the app builds and runs, and *Add Google account* says sign-in isn't set up. The project's Android client must also know the signing key: it has the SHA-1 of robin-laptop's Android Studio debug key, so builds signed with another key need their own Android client. While the project is in Testing, only its listed test users can sign in, and Google ends their Drive access after 7 days; PhotoXT then shows *Reconnect*.

### Maps API key (optional)

The viewer's details sheet draws a small map of where a photo was taken. That needs a Google Maps SDK for Android key. Without one the app still builds and runs, and the sheet shows the coordinates with an *Open in Maps* button instead. To add a key, keep it out of git: put `PHOTOXT_MAPS_API_KEY=<your key>` in `~/.gradle/gradle.properties` (on Windows `%USERPROFILE%\.gradle\gradle.properties`), or set an environment variable of that name, then rebuild. Restrict the key in Google Cloud to Android apps with package `com.photoxt.app` and your signing certificate's SHA-1.

Shared workspace rules live in the Projects hub: [README](../../../README.md) · [AGENTS](../../../AGENTS.md) · [conventions](../../../conventions.md) · [skills](../../../skills.md). Parent project: [Android](../README.md).
