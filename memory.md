# Memory — PhotosXT

Durable facts and decisions that a future session would otherwise have to rediscover. One line each; delete a line when it stops being true. Status and in-progress work belong in `.session/` logs, not here.

## Decisions
- App name is **PhotoXT**; the folder and the Claude project are named PhotosXT.
- Stack: native Android, Kotlin + Jetpack Compose (chosen over Kotlin Multiplatform and Flutter, 2026-11-04).
- Audience: private first (family, up to 100 Google accounts), kept Play Store-ready for a later public launch (2026-11-04).
- SDK levels: minSdk 30 (Android 11), targetSdk and compileSdk 37 (Android 17). Google Play requires target 36+ since 31 Aug 2026.
- Backend: Firebase in asia-south1 (Auth, Firestore, Functions, Hosting, Storage, FCM, App Check). It is used only for public albums, boards, share-link records and invites.
- Drive scopes: `drive.readonly` when the first folder is added, upgrade to `drive` on the first write, `drive.appdata` for settings sync. `drive.file` + Google Picker can't list a picked folder's contents (re-test in the Phase 3 spike).
- Data boundary: Drive content, Drive metadata and OAuth tokens never leave the device. Why: restricted-scope data stored on or sent to servers triggers Google's yearly security assessment.
- Sharing with people uses Drive permissions; the Shared Albums "With me" view reads Drive `sharedWithMe`. Links are Android App Links on the Firebase Hosting domain (Firebase Dynamic Links shut down in Aug 2025).
- Google Cloud for private use: in Testing status, grants expire every 7 days. In production but unverified, users see a warning and the project is capped at 100 users.
- Two editions (2026-11-04): PhotoXTPro is the full spec, including public media boards. PhotoXT is the same spec without media boards; Public Albums stay, since only boards were asked to go. Section and phase numbers match in both specs.
- One codebase for both editions (Robin, 2026-11-04). Build PhotoXT now; add the `pro` product flavor (PhotoXTPro) in this repo when board work starts (Phase 12). No flavors before that.
- Git and CI (Robin, 2026-11-04): this folder is the repo root, branch `main`. CI is `.github/workflows/ci.yml`; until a GitHub remote exists, run its command locally (see README "Build and check").
- Toolchain (pinned 2026-11-04 in `gradle/libs.versions.toml`): Gradle 9.8.2, AGP 9.5.0 with built-in Kotlin (never apply `org.jetbrains.kotlin.android`), Kotlin 2.4.21, KSP 2.3.13, Hilt 2.60.1, Compose BOM 2026.09.00 (Material 3 1.4.0), Navigation 3 1.2.0. Builds need JDK 17+; CI uses 21.
- `applicationId` and namespace are the placeholder `com.photoxt.app` until open decision 1 (spec §9) is settled, before Phase 11.
- Navigation: one Navigation 3 back stack per tab, each with its own saveable-state decorator, so a tab keeps its state while hidden (nav3-recipes "multiple back stacks"). Back exits through Pictures.
- One UI header: `PxtCollapsingHeader` in core/designsystem on Material 3's exit-until-collapsed scroll behaviour. Expanded = 40% of the window height, collapsed = 64 dp, and no expansion on windows under 480 dp tall.
- Logging: only `PxtLog` (core/common). It redacts tokens, e-mails, URIs, paths, media file names and coordinates; debug StrictMode reports through it.
- No Android cloud backup or device-to-device transfer (`allowBackup`/`fullBackupContent` false, `data_extraction_rules.xml` excludes everything). Settings restore through the user's Drive app-data folder (spec §4.8) instead.
- Theme: dynamic colour by default; without it, the Material 3 baseline scheme until the accent setting exists (§4.20). AMOLED black is `PxtTheme(amoledBlack = true)` and gets a user setting with the other theme options.
- Icons: Material Symbols Rounded, as vector drawables in core/designsystem (`ic_tab_*`). Not `material-icons-extended`.
- `baselineprofile/` holds both the Baseline Profile generator and the Macrobenchmark startup test (Android Studio's layout); there is no separate `benchmark/` module.
- Quality gates run from the root: Spotless with ktlint `android_studio` style (100 columns, no trailing commas), detekt 1.23.8, and Android Lint with warnings as errors (version-age checks and `ObsoleteLintCustomCheck` disabled).
- Tests: Robolectric runs Android 17, which needs `--add-opens java.base/jdk.internal.access` (set in build-logic) and espresso-core 3.7+ (pinned in app). Screenshot goldens live in `app/src/test/screenshots`; re-record with `./gradlew recordRoborazziDebug`. Screenshot tests click with semantics actions, because touch ripples differ between runs.

## Known limits
- detekt 1.23.8, the latest stable, calls Gradle APIs that Gradle 10 removes. Move to detekt 2 once it is stable.
- At 200% text on a 360 dp phone, bottom-bar labels wrap mid-word (Material 3 default). Revisit in the accessibility pass.
- Git from a Cowork session: its device shell can't delete files, so git there leaves `.lock` files. Commit from Windows (Android Studio or git), or grant the session delete permission first.

## Where things live
- Specs: [photoxt-build-prompt.md](photoxt-build-prompt.md) (PhotoXT) and [photoxtpro-build-prompt.md](photoxtpro-build-prompt.md) (PhotoXTPro) are the source of truth. Copies sit in the Claude project knowledge under `claude/` with the same names; re-upload them after editing the folder copies.
- Code: `app/` (shell, navigation, tabs), `core/common` (PxtLog), `core/designsystem` (theme, header, icons), `core/ui` (EmptyState), `baselineprofile/`, `build-logic/` (convention plugins).
