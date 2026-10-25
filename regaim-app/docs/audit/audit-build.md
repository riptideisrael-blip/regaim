# Build infrastructure audit — checkpoint

Project: `/workspace/scratch/324cbd36f07b/project/regaim-app`

## Baseline

- Android application, Kotlin 1.9.24, Android Gradle Plugin 8.5.2.
- `compileSdk=34`, `targetSdk=34`, `minSdk=26`, Java/Kotlin target 17.
- Debug signing uses the uploaded prototype keystore. Release signing/distribution is not configured.
- Project includes `gradle.properties` with AndroidX and JVM configuration.
- No Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) in uploaded project. Hidden `.github/workflows/build.yml` provisions Gradle 8.9, downloads the missing model and assembles debug. It publishes a prototype debug release on every main push; no PR test/lint workflow was present.
- No automated Kotlin tests or Android instrumentation tests in uploaded source.

## Actual execution evidence

Command: `./gradlew --no-daemon :app:assembleDebug`

Result: exit 127, `/bin/bash: ./gradlew: No such file or directory`.

Initial environment: OpenJDK 17 installed; Gradle, Kotlin CLI, Android SDK, sdkmanager, adb and dependency caches absent.

Safe official toolchain provisioning succeeded in scratch. Official Gradle 8.7 and SDK command-line tools archives pass `unzip -tq`. SDK 34, build-tools 34.0.0 and platform-tools installed; sdkmanager exit 0. Network proxy caused intermittent connect timeouts and SDK transfer truncation; a resumed official download completed with verified archive integrity.

Second baseline command using installed Gradle: `/workspace/scratch/324cbd36f07b/toolchain/gradle-8.7/bin/gradle --no-daemon :app:assembleDebug --stacktrace`

Result: exit 1, AGP `com.android.application:8.5.2` plugin could not resolve. This was an environment network failure, not proof of a source compile defect. Full exact log: `/workspace/scratch/324cbd36f07b/baseline-build.log`. A subsequent run configures the existing environment's HTTP proxy for Gradle JVM and downloads dependencies successfully; its log is `/workspace/scratch/324cbd36f07b/baseline-build-proxy.log`.

## Post-audit infrastructure changes

- Official generated Gradle 8.7 wrapper with pinned distribution SHA-256.
- Existing YAMNet model bundled from original TensorFlow endpoint (4,126,810 bytes), verified TFLite identifier and pinned SHA-256. This repairs a missing runtime asset without replacing Capture/AI behavior.
- `scripts/prepare-model.sh`: verifies an existing model, otherwise downloads atomically and checks pinned digest, minimum size and `TFL3` identifier. Hash is pinned to the upstream artifact downloaded on 2026-10-06, not described as an upstream-published checksum.
- `scripts/check.sh`: unit tests, debug lint, debug and unsigned release assembly.
- JUnit4 dependency for meaningful pure Kotlin HighlightPolicy tests being supplied by the capture/media implementation agent.
- CI adds PR checks/unit tests/lint/debug/release assembly; maintains existing prototype debug publication only for main pushes. Unsigned release is a build artifact, not a production-signed distributable.

Clean original uploaded-source baseline succeeds after environment setup: `:app:assembleDebug`, exit 0, `BUILD SUCCESSFUL in 1m 36s`, 37 tasks. Built from independently extracted untouched archive at `/workspace/scratch/324cbd36f07b/baseline/regaim-app`. Exact evidence: `/workspace/scratch/324cbd36f07b/baseline-source-build.log`. The original source compiles but lacks YAMNet in its assets, so build success does not prove original laughter detection works at runtime.

First updated application integrated check passed both `:app:compileDebugKotlin` and `:app:compileReleaseKotlin`; debug APK assembly succeeded. 31 JVM tests passed (HighlightPolicy 13, MemoryFacts 10, MemoryAnalysisMerge 8). Later source changes add stronger merge tests and Profile persistence coverage, so the final complete rerun is required. Wrapper execution `./gradlew --version` also succeeds.

First integrated check stopped at debug lint: 9 errors and 187 warnings; exact evidence in `/workspace/scratch/324cbd36f07b/final-build.log` and `app/build/reports/lint-results-debug.xml`. Issues identified: 2 suspicious indentation in CircleActivity; 6 AppCompat `android:tint` versus `app:tint` layout attributes; 1 false positive for the legacy Intent quick-settings overload (already correctly guarded SDK < 34 with SDK >= 34 PendingIntent implementation). Fixes are local: explicit indentation/braces, AppCompat tint attributes, and a narrow onClick lint annotation explaining the pre-existing API guard. No global lint baseline or disable was introduced. Release vital lint passed during this run; release packaging was not claimed complete.

SDK emulator and API 34 default x86_64 image installed successfully. No `/dev/kvm` hardware acceleration is available. A headless software emulator reached adb connectivity but did not finish Android framework boot. A premature APK installation failed inside the emulator OS because PackageManagerInternal was not initialized. During compilation, combined memory reached 8,189 MiB of the 8,192 MiB container limit, so the software emulator was stopped to allow reliable build completion. No application was successfully installed/launched; no native UI/navigation screenshots or runtime checks are claimed. Live backend integration and physical capture/audio/AI detection remain unverified.

## Final frozen-source results

After the final MemorySocial account/circle/server scope guard, the exact current-source command completed:

`./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --stacktrace`

The scratch environment supplied Android SDK 34 through `ANDROID_HOME` and the pre-existing environment HTTP proxy through Gradle JVM system properties. These workspace-specific settings were not embedded in the project.

- **Build: PASS**, `BUILD SUCCESSFUL in 1m 6s`, 99 tasks (25 executed, 74 up to date).
- **Compilation: PASS**, debug and release Kotlin/Android resources.
- **Unit tests: 39 passed, 0 failures, 0 errors.** HighlightPolicy 13; MemoryAnalysisMerge 11; MemoryFacts 10; ProfilePersistence 5.
- **Lint: PASS**, 0 errors; 188 warnings remain and are visible in the report. No broad lint baseline/disable.
- **Debug APK:** `/workspace/scratch/324cbd36f07b/project/regaim-app/app/build/outputs/apk/debug/app-debug.apk` (90,115,055 bytes), `versionCode=15`, `versionName=0.15`, package `com.regaim.app`.
- **Debug signature:** verified with `apksigner`; exact prototype certificate identity matches the original baseline APK, preserving compatible test upgrades.
- **Release APK:** `/workspace/scratch/324cbd36f07b/project/regaim-app/app/build/outputs/apk/release/app-release-unsigned.apk` (88,275,942 bytes). This is unsigned; production signing/distribution is not configured.
- Both APKs contain the verified original YAMNet model with pinned SHA-256 `10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de`.

Final exact log: `/workspace/scratch/324cbd36f07b/final-validation-build.log`. Machine-readable evidence, artifact digests and test counts: `docs/audit/build-verification.json`. Earlier successful validation before final scope guard is preserved in `final-validation-before-scope-guard.log`; earlier lint-failing run in `final-build.log`.

## Files changed by build infrastructure work

`app/build.gradle.kts`, `.github/workflows/build.yml`, `.gitignore`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `scripts/prepare-model.sh`, `scripts/check.sh`, `BUILDING.md`, `app/src/main/assets/yamnet.tflite`, and the narrow lint annotation in `app/src/main/java/com/regaim/app/SaveTileService.kt`.

CaptureService, rolling buffer, audio/speech/YAMNet detector code were not rewritten or modified by this infrastructure scope.

## Primary compatibility source

Android Developers AGP 8.5 release notes: https://developer.android.com/build/releases/agp-8-5-0-release-notes

This release requires Gradle 8.7 and JDK 17, supports API 34 and uses SDK Build Tools 34.0.0. We are keeping the existing AGP/Kotlin versions for audit and verification rather than upgrading the toolchain as an unrelated change.

## Remaining verification

Toolchain provisioning, baseline debug build and final debug/release/unit-test/lint validation are complete. Native UI and physical camera/microphone/background tests remain unverified. Auth, AI provider and family-v3 database policy tests require actual staging configuration; no live external service changes were made. Release signing/distribution remains unconfigured.
