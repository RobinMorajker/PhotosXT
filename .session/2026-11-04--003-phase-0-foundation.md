# 2026-11-04 — 003 — PhotoXT Phase 0: Foundation

- **Tool:** Claude (Cowork), cloud session linked to robin-laptop. Built and tested in the cloud workspace (Linux, JDK 21, Android SDK 37); delivered into this folder.
- **Goal:** Build Phase 0 of [photoxt-build-prompt.md](../photoxt-build-prompt.md).

## Scope (spec §0 rule 2)
Gradle catalog and convention plugins; modules `app`, `core/common`, `core/designsystem`, `core/ui`; Hilt and Compose; Navigation 3 with five tabs and empty states, each tab keeping its state; light, dark and AMOLED themes with dynamic colour; One UI-style collapsing headers; redacting logger and debug StrictMode; detekt, ktlint and Lint; GitHub Actions CI; baseline-profile and benchmark module. Checks: the CI command, a Robolectric navigation test and Roborazzi screenshots.

## Decisions (asked before starting)
- One codebase; build PhotoXT now and add the `pro` flavor at Phase 12.
- This folder becomes a git repo with a first commit; CI runs locally until Robin pushes to GitHub.
- The rest are in [memory.md](../memory.md): toolchain, navigation, header, logging, backups, icons, quality gates, tests.

## Done
- Gradle 9.8.2 wrapper; `gradle/libs.versions.toml` with every version and the SDK levels; `build-logic` with three convention plugins (`photoxt.android.application`, `.library`, `.compose`). AGP 9.5 built-in Kotlin, Kotlin 2.4.21.
- `core/common`: `PxtLog`, which redacts tokens, e-mails, URIs, paths, media file names and coordinates (3 tests).
- `core/designsystem`: `PxtTheme` (light, dark, AMOLED black, dynamic colour), `PxtCollapsingHeader` (One UI-style: large centred title in the top 40%, collapses to a 64 dp title), tab icons from Material Symbols Rounded.
- `core/ui`: `EmptyState`.
- `app`: Hilt application with debug StrictMode reporting through `PxtLog`; edge-to-edge `MainActivity`; `NavigationSuiteScaffold` (bottom bar, rail from 600 dp) over Navigation 3 with one back stack per tab; five tabs (Pictures, Albums, Shared → "Shared Albums", Public → "Public Albums", Library) with empty states; launcher icon; no Android backup or device transfer.
- `baselineprofile`: Baseline Profile generator and a cold-start Macrobenchmark (needs a connected phone to run).
- Tests: `AppNavigationTest` (opens on Pictures; a collapsed Pictures header stays collapsed after visiting Albums and after the activity is recreated; Albums starts fresh; back from Library returns to Pictures). Screenshot tests for all five tabs, dark, AMOLED, and 200% text.
- Quality: Spotless + ktlint, detekt and Lint (warnings as errors) all clean. `.github/workflows/ci.yml` runs the README command.

## Checks run
- The CI command (`./gradlew spotlessCheck detekt lintDebug testDebugUnitTest assembleDebug :baselineprofile:assemble`) passed, then passed again from a clean copy of the tracked files with the build cache off. 13 tests, 0 failures.
- Screenshot tests passed twice in a row against the recorded images (no flakiness).

## On a real phone (Galaxy A15 5G, SM-A156E, wireless adb from robin-laptop)
- The debug APK installed and `MainActivity` started; logcat shows the first frame drawn, no crash, and the Baseline Profile installer running.
- Logging check (spec §7): nothing PhotoXT logged contains a token, e-mail, file name or location. The only app-tagged output was StrictMode reports, routed through `PxtLog`; all of them come from Samsung's own `IdsController` reading preferences on the main thread, not from PhotoXT code.
- The first frame of this cold debug start took about 2.1 s (JIT, debug build, phone locked). Not a measure of the §5.8 budget, which is for a release build with a Baseline Profile (checked in Phase 15).
- Once Robin unlocked it, the app showed Pictures in the phone's dark theme: large title in the top 40%, empty state below, bottom bar with Pictures selected (screenshot over adb). The tab-state check needs taps, which adb from this session cannot make, so it was not repeated on the phone.
- With 3-button navigation the system draws its contrast scrim under the bottom bar, a slightly darker band. Turning it off (`window.isNavigationBarContrastEnforced = false`) is a one-line polish for when `MainActivity` is next touched.

## Phase 0 "Done when"
- CI is green: the identical command is green locally. The GitHub Actions run waits for a remote (see Next).
- Launches to Pictures, and tabs keep their state: proven by `AppNavigationTest` under Robolectric (Android 17). On the phone it opens on Pictures; checking tab state there by hand is left to Robin (see Next).
- Each tab has a screenshot test: yes (`TabScreenshotTest`, one per tab).

## Skipped, and when to add
- Search field, account avatar and overflow menu in the header (§4.1): with search (Phase 13) and accounts (Phase 3).
- PhotoXT accent colour scheme and the theme setting that exposes AMOLED: with Settings (Phase 6).
- Splash-screen library: the system splash is enough until startup waits on data.
- ViewModel scoping per navigation entry: with the first ViewModel (Phase 1); marked with a `myopt:` comment in `Navigation.kt`.

## Problems met
- Maven Central answered HTTP 429 to this cloud session, so the session used Google's Maven Central mirror through a Gradle init script outside the repo. Nothing in the repo depends on it.
- Robolectric on Android 17 needed `--add-opens java.base/jdk.internal.access` and espresso-core 3.7.
- Touch clicks left ripples that made screenshots differ between runs; screenshot tests now click through semantics actions.

## Next
- On the phone, open PhotoXT, drag Pictures up so its title shrinks, tap Albums, tap Pictures: the title should still be small.
- Push to a private GitHub repo from Windows, so CI runs there:
  `git remote add origin https://github.com/<you>/PhotosXT.git` then `git push -u origin main`.
- If the Projects folder is synced with Syncthing, ignore `build`, `.gradle` and `.kotlin` folders there (`.stignore`), or every build syncs hundreds of MB.
- Phase 1 (Device gallery).
