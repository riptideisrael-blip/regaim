# Building Regaim

Prerequisites: JDK 17, Android SDK platform 34, SDK Build Tools 34.0.0, and `ANDROID_HOME` pointing to the SDK. Android Studio can install these SDK packages. The project includes a checksum-pinned Gradle 8.7 wrapper; do not depend on a global Gradle installation.

## Check and build

On Linux/macOS, with Python 3 and curl installed:

```sh
./scripts/check.sh
```

Or use these tasks directly (including on Windows with `gradlew.bat`):

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

The YAMNet model is included in `app/src/main/assets/yamnet.tflite`. `scripts/prepare-model.sh` verifies its pinned SHA-256 and TFLite identifier and can restore it from the project's original TensorFlow download URL if needed. A changed/truncated upstream file fails verification instead of silently replacing the model.

## Outputs

- `app/build/outputs/apk/debug/app-debug.apk`: prototype debug APK using the existing prototype signing identity, so it can update compatible existing test installations.
- `app/build/outputs/apk/release/app-release-unsigned.apk`: release assembly for validation. Production signing is not configured.
- `app/build/reports/tests/testDebugUnitTest/`: unit-test results.
- `app/build/reports/lint-results-debug.html`: Android lint report.

Unit tests verify highlight range validation, evidence handling, known-versus-unknown memory facts and dynamic profile persistence. They use real in-memory preferences and JVM JSON for persistence assertions; Android mockable constructors return defaults. These tests do not verify camera/microphone hardware, foreground capture, actual speech/laughter detection, a live account or family backend permissions.

CI runs unit tests, debug lint and debug/release assembly on pull requests and main pushes. Existing prototype debug release publication remains limited to successful main pushes. Pull requests do not publish releases.
