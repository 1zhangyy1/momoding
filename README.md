<p align="center">
  <img src="android-app/app/src/main/res/drawable-nodpi/momoding_head.png" width="144" alt="Momoding" />
</p>

# Momoding

Momoding is a local-first AI coding workspace for Android. The agent loop runs on the phone,
connects to OpenRouter with the user's own API key, and can work only with files the user
explicitly selects through Android's Storage Access Framework.

[简体中文](README.zh-CN.md)

> **Developer preview:** the repository is suitable for review and local development, but the app
> is not yet a production or Play Store release. The public build intentionally excludes the
> experimental Linux/PRoot runtime and remote host service. It does not expose terminal or test
> execution tools to the model.

## What works

- On-device Pi agent loop in QuickJS; Node.js is used only to build and test the bundle.
- OpenRouter bring-your-own-key setup. Credentials are encrypted with an Android Keystore-backed
  AES-GCM key and stored in the app's no-backup directory.
- Task, plan, goal, child-agent, skill, attachment, and recovery state persisted with Room.
- Explicit Android SAF folder authorization with task-scoped opaque file identities.
- Separate approval for reading file contents and a reviewed diff/confirmation step before writes.
- Photo Picker, selected photo-library metadata, camera capture, share-sheet text/files, and
  bounded text attachments.

## Trust boundaries

| Boundary | Current behavior |
| --- | --- |
| API key | Encrypted locally; never committed or embedded in the APK |
| Project folders | Available only after the user selects a folder through Android SAF |
| File contents | Require a separate task-scoped read approval |
| File changes | Prepared privately, shown as a diff, then committed only after confirmation |
| Photos | Photo Picker works without broad library access; library metadata follows Android permission state |
| Terminal | Not available in the public build |
| Remote host | Not included in this repository |

This is a security design, not a formal security proof. Please report suspected vulnerabilities
privately as described in [SECURITY.md](SECURITY.md).

## Repository layout

```text
android-app/        Android application, Room storage, policy, UI, and tests
mobile-runtime-js/  Pinned Pi runtime bundle built for QuickJS
wire/               Shared protocol schemas and the Kotlin wire contract
scripts/            Repository verification and public-release checks
```

The exact public boundary is recorded in [OPEN_SOURCE_SCOPE.md](OPEN_SOURCE_SCOPE.md). Internal
research, device captures, credentials, private host code, and historical validation artifacts are
not part of this repository.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Security model](docs/SECURITY_MODEL.md)
- [Release process](RELEASING.md)
- [Android module](android-app/README.md)
- [Pi runtime module](mobile-runtime-js/README.md)
- [Wire contracts](wire/README.md)

## Prerequisites

- JDK 17
- Android SDK Platform 37 and Build Tools 37.0.0
- Node.js 22.22.3 and npm 10.9.8
- Git and `rg` (ripgrep)

Set `JAVA_HOME` and `ANDROID_HOME`; do not commit `local.properties`.

## Build

```bash
npm ci --prefix mobile-runtime-js
npm run check --prefix mobile-runtime-js

JAVA_HOME=/path/to/jdk-17 \
ANDROID_HOME=/path/to/android-sdk \
./android-app/gradlew -p android-app \
  testDebugUnitTest lintDebug assembleDebug assembleRelease
```

The debug APK is written to
`android-app/app/build/outputs/apk/debug/app-debug.apk`.

Install it on an Android 11+ test device:

```bash
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
```

Open **Settings → Provider**, enter an OpenRouter API key, choose a model, and then authorize only
the folders a task should use.

## One-command verification

```bash
./scripts/verify.sh
```

The command checks the public repository boundary, rebuilds the runtime in temporary directories,
runs the JavaScript and Kotlin/JVM suites, runs all Android unit tests, compiles the instrumentation
tests, runs lint, assembles both debug and release APKs, and inspects the release APK's permissions,
exported components, and packaged artifacts. It does not require a real API key.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Use a public issue for normal
bugs and feature requests, but never post API keys, diagnostics archives, private project files, or
security reports. Security reports follow [SECURITY.md](SECURITY.md).

## License

Momoding source code is available under the [MIT License](LICENSE). Third-party components keep
their original licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
