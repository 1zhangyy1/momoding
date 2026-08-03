# Momoding Android application

This directory contains the independent open-source Momoding Android application. It is not an
official OpenAI application.

## Product boundaries

- Package namespace and application ID: `app.momoding`
- Minimum Android version: Android 11 / API 30
- Provider: user-configured OpenRouter credential
- Durable state: Room with the bundled SQLite driver
- Local agent runtime: Pi AgentHarness in QuickJS
- Project access: Android SAF plus optional shared-storage access
- Optional device capabilities: calendar, contacts, foreground location, clipboard, Momoding-owned
  notifications, media metadata/mutations, MediaProjection screen capture, accessibility UI
  inspection/actions, and Shizuku read-only package inspection
- Debug project runtime: pinned PRoot/talloc/Alpine build

Android owns credentials, provider HTTP, permission state, policy checks, tool execution, and
durable side-effect records. JavaScript does not receive the provider API key.

## Prerequisites

- JDK 17 through `JAVA_HOME`
- Android SDK Platform 37.0 (`platforms;android-37.0`) and Build Tools 37.0.0 through
  `ANDROID_HOME`
- Android NDK 28.2.13676358 for the debug phone-local Linux runtime
- Node.js 22.22.3 for regenerating the tracked Pi runtime asset

`local.properties` is ignored and must not be committed.

## Build and verify

From the repository root:

```bash
npm ci --prefix mobile-runtime-js
npm run check --prefix mobile-runtime-js

JAVA_HOME=/path/to/jdk-17 \
ANDROID_HOME=/path/to/android-sdk \
./android-app/gradlew -p android-app \
  testDebugUnitTest \
  compileDebugAndroidTestKotlin \
  lintDebug \
  assembleDebug \
  assembleRelease
```

The APK outputs are under `android-app/app/build/outputs/apk/`.

The debug build downloads pinned native/runtime inputs and verifies their digests. See
[`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) before redistributing an APK.

## Test layers

- JVM/Robolectric tests cover state, policy, persistence, provider encoding, recovery, and tool
  routing.
- Device-dependent instrumentation suites are maintained in private validation and are not included
  in the public source snapshot. They require explicit emulators, physical devices, permissions, or
  Shizuku setup.
- `lintDebug` is configured to fail on warnings.
- `scripts/verify-release-apk.mjs` inspects the built release manifest, permissions, exported
  components, and packaged artifacts.

The repository-wide command is `./scripts/verify.sh`.
