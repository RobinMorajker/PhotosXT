# PhotosXT

**PhotoXT** is a native Android gallery (Kotlin + Jetpack Compose) built to beat Samsung Gallery. It adds selected Google Drive folders as streamed albums with on-device thumbnails and a 30-day download cache. It also covers editing, collages, casting to TVs, and albums that are private by default and can be shared or made public. **PhotoXTPro** is the same app plus public media boards (image, video and media boards that other users post to).

**Status (2026-11-16):** Phase 6 (Organize, history and settings sync) of PhotoXT is built and checked on the Galaxy A15. Every list sorts both ways: Pictures, each album and each Library list by date taken, added or modified, name (IMG_2 before IMG_10), size or a custom drag order, and the Albums tab by name, newest item, date created, item count, size or custom order, with pinned albums and items always on top; on the phone a 24,000-item album switches sorts in 0.3–0.5 s (Custom order's first pick 0.8 s) and scrolls smoothly in a release build. Favorites sync with the phone's gallery and Drive's stars; albums and items can be pinned, and albums put on the home screen. Bookmarks keep items, albums and video moments in collections with notes. History shows Recently viewed and Continue watching, can be paused and deletes itself after 12 months. Hidden items and albums, and an encrypted vault that removes the original, open behind the fingerprint or screen lock. Album groups, virtual albums and smart albums from rules are in the Albums tab, and settings, sorts, pins, bookmarks and history come back after a reinstall from the user's own Drive (tried on the phone; the copy is gzipped, about 150 KB with a 24,000-item Custom order). CI is green (189 tests, no Lint or detekt findings). Committed locally; CI runs on GitHub once it is pushed. Next step: Phase 7 (Image editor).

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
