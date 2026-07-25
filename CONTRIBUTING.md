# Contributing to Momoding

Thank you for helping improve Momoding.

## Before opening a change

- Use an issue for substantial features or changes to security/permission boundaries.
- Keep pull requests focused and explain the user-visible behavior.
- Never include real API keys, device data, private project files, captures, or generated reports.
- Preserve the documented Android capability truth. A feature must not be shown as available when
  its runtime or permission path is absent.

## Development

Use the toolchain versions in [README.md](README.md), then run:

```bash
./scripts/verify.sh
```

For Android-only iteration:

```bash
JAVA_HOME=/path/to/jdk-17 \
ANDROID_HOME=/path/to/android-sdk \
./android-app/gradlew -p android-app testDebugUnitTest lintDebug assembleDebug
```

## Pull requests

A pull request should include:

- a concise problem statement and scope;
- tests for behavior changes;
- screenshots only when UI changed, with personal/device information removed;
- documentation and third-party notice updates when applicable;
- reviewed lockfile and Gradle verification-metadata changes for dependency updates;
- confirmation that `./scripts/verify.sh` passes.

Maintainers may ask for a smaller change when a pull request mixes product, refactor, dependency,
and formatting work.

## Dependency updates

Dependencies are pinned by npm and Gradle lockfiles. Gradle also verifies downloaded artifact
metadata and binaries by SHA-256. After intentionally changing a Gradle dependency, regenerate the
affected metadata only after reviewing the upstream source and requested version:

```bash
./wire/kotlin-contract/gradlew -p wire/kotlin-contract \
  --write-verification-metadata sha256 test

./android-app/gradlew -p android-app \
  --write-verification-metadata sha256 \
  testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug assembleRelease
```

Review every new checksum and dependency before committing it. A checksum change without an
intentional dependency change must be treated as a supply-chain failure.

By contributing, you agree that your contribution is licensed under this repository's MIT License.
