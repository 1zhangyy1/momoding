# Momoding Android app

This module owns the Android product surface: Compose UI, Room persistence, encrypted provider
credentials, Android permission/capability state, SAF file access, attachment handling, and the
phone-local Pi bridge.

The app namespace and application ID are both `app.momoding`. The minimum supported version is
Android 11 (API 30).

## Build

From the repository root:

```bash
JAVA_HOME=/path/to/jdk-17 \
ANDROID_HOME=/path/to/android-sdk \
./android-app/gradlew -p android-app \
  testDebugUnitTest lintDebug assembleDebug assembleRelease
```

`SOURCE_REVISION` may be set to a 40-character Git commit when building outside a Git checkout.
`local.properties`, signing keys, APKs, reports, and device captures are intentionally ignored.

## Public-build limitation

The source tree retains experimental scaffolding for an app-private workspace command runtime,
but the public runtime bundle starts task sessions with project command tools disabled. No PRoot
binary or Alpine filesystem is packaged, and the model is explicitly told that terminal commands
and test execution are unavailable.

Do not enable that runtime in a distributed build until its complete corresponding source,
license notices, update policy, ABI support, and device validation are ready.
