# PhotosXT

**PhotoXT** is a native Android gallery (Kotlin + Jetpack Compose) built to beat Samsung Gallery. It adds selected Google Drive folders as streamed albums with on-device thumbnails and a 30-day download cache. It also covers editing, collages, casting to TVs, and albums that are private by default and can be shared or made public. **PhotoXTPro** is the same app plus public media boards (image, video and media boards that other users post to).

**Status (2026-11-05):** Phase 1 (Device gallery) of PhotoXT is built and its checks pass locally. Pictures shows every photo and video on the phone by date, with pinch to change density, a fast scroller, filters, and multi-select with drag-select and share; Albums lists the phone's folders; full, limited and denied access all work. On a Galaxy A15 with 23,781 items, a new camera photo appears within 2 s, a photo deleted elsewhere is gone on resume, and scrolling stays smooth. Committed locally; CI runs on GitHub once it is pushed. Next step: Phase 2 (Viewer and player).

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

Shared workspace rules live in the Projects hub: [README](../../../README.md) · [AGENTS](../../../AGENTS.md) · [conventions](../../../conventions.md) · [skills](../../../skills.md). Parent project: [Android](../README.md).
