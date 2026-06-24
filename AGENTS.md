# AGENTS.md

Single-module Android app (`:app`) wrapping the website `https://seerinfo.yuyuqaq.cn/` in a WebView. Kotlin, ViewBinding. See `README.md` for feature list and the source-file map under `app/src/main/java/com/hurrywang/seerinfo/`.

## Commands (Windows / pwsh)

- Build debug: `.\gradlew.bat assembleDebug`
- Build release (unsigned locally): `.\gradlew.bat assembleRelease`
- Unit tests: `.\gradlew.bat test` (JVM tests in `app/src/test/`)
- Instrumented tests: `.\gradlew.bat connectedAndroidTest` (needs a running device/emulator; `app/src/androidTest/`)
- Android lint: `.\gradlew.bat lint` (no ktlint/detekt configured)
- Only example/placeholder tests exist today.

## Build environment (non-default — easy to get wrong)

- `compileSdk`/`targetSdk` = 36, `buildToolsVersion` = `36.1.0` must be installed locally; `minSdk` = 24.
- Source/JVM target is **Java 11** (`compileOptions` + `kotlinOptions.jvmTarget = "11"`). CI builds with JDK 17 but the code must stay Java-11 compatible — do not use newer-than-11 APIs.
- All dependencies and versions live in `gradle/libs.versions.toml` (version catalog). Add/upgrade deps there, referenced as `libs.*`; do not hardcode versions in `app/build.gradle.kts`.
- Repositories are centralized in `settings.gradle.kts` with `RepositoriesMode.FAIL_ON_PROJECT_REPOS` (aliyun mirrors + jitpack, for CN networks). Do **not** add `repositories {}` blocks in module build files — the build will fail.
- `local.properties` (gitignored) provides `sdk.dir`.

## Release / versioning

- Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
- Release is automated by pushing a tag matching `v*` (e.g. `v1.27`); `.github/workflows/android-release.yml` builds, **signs** (secrets `SIGNING_KEY`/`KEY_ALIAS`/`KEYSTORE_PASSWORD`/`KEY_PASSWORD`), and publishes a GitHub Release. Local `assembleRelease` is unsigned but the `release` build type enables R8 (`isMinifyEnabled = true`, `isShrinkResources = true`) with `proguard-rules.pro` — keep that file in sync when adding reflection/JS-bridge surfaces.
- `androidResources.localeFilters = ["zh", "en"]` restricts packaged locale resources; only `values/` (Chinese) strings exist today (no `values-en/`).

## App-specific gotchas

- JS bridge: `WebAppInterface` is injected as the JS object `Android`; current methods are `clearAllCache()`, `showToast(msg)`, `share(jsonStr)` (expects `{"title","url"}`, fires an `ACTION_SEND` chooser), and `getAppVersion()`. New web-callable methods require the `@JavascriptInterface` annotation; WebView mutations must run on the main thread (`Dispatchers.Main`), as existing methods do.
- The loaded URL is hardcoded in `MainActivity.setupWebView` (`MainActivity.kt`).
- `setupSettings` enables multiple windows; the `WebChromeClient.onCreateWindow` in `MainActivity.setupWebViewClients` routes `target=_blank`/popup links to an external browser via `ACTION_VIEW`. Back button is handled by an `OnBackPressedCallback` that calls `webView.goBack()` until history is empty.
- Custom UA: `setupUserAgent` appends `seerinfo-android/<versionName> (<pkg>; vc=<versionCode>)` to the default UA — the website can sniff this to detect the native client.
- WebView config is split into extension functions in `WebViewSetupExtensions.kt` (settings/UA/download listener); image saving (network + Base64, to `Pictures/Seerinfo`) lives in `ImageDownloader.kt`; pull-to-refresh scroll coupling is in `RefreshAwareWebView.kt`.
- Edge-to-Edge inset padding is applied manually in `MainActivity.onCreate` for Android 15+; keep it when touching layout/window code.
