# Third-party notices

Momoding-authored source is MIT licensed. Third-party code, libraries, icons, patches, and
build-fetched runtime components retain their upstream licenses. This summary is not a substitute
for the upstream license and NOTICE files.

## Android and JavaScript runtime

| Component | Version | License | Upstream |
| --- | --- | --- | --- |
| AndroidX, Jetpack Compose, Room, DataStore, Navigation, Lifecycle | pinned in `libs.versions.toml` | Apache-2.0 | https://github.com/androidx/androidx |
| Kotlin stdlib, coroutines, serialization | pinned in Gradle locks | Apache-2.0 | https://github.com/JetBrains/kotlin |
| OkHttp | 5.4.0 | Apache-2.0 | https://github.com/square/okhttp |
| quickjs-kt | 1.0.5 | Apache-2.0 | https://github.com/dokar3/quickjs-kt |
| QuickJS engine | bundled by quickjs-kt | MIT | https://bellard.org/quickjs/ |
| SQLite | bundled by AndroidX SQLite | Public domain | https://www.sqlite.org/copyright.html |
| Multiplatform Markdown Renderer | 0.38.1 | Apache-2.0; portions MIT | https://github.com/mikepenz/multiplatform-markdown-renderer |
| Shizuku API and provider | 13.1.5 | Apache-2.0 | https://github.com/RikkaApps/Shizuku |
| Pi Agent Core and Pi AI | 0.80.6 | MIT | https://github.com/earendil-works/pi |
| Phosphor Icons subset | 2.1 source family | MIT | https://github.com/phosphor-icons/core |

The Phosphor attribution and embedded MIT text are also available in
[`android-app/PHOSPHOR-NOTICE.md`](android-app/PHOSPHOR-NOTICE.md).

Shizuku's upstream license does not grant trademark rights. Momoding uses the Shizuku name only to
identify the compatible third-party service and does not use Shizuku artwork or claim affiliation.

## Debug phone-local Linux runtime

The Android debug build invokes [`scripts/build-phone-local-linux-runtime.sh`](scripts/build-phone-local-linux-runtime.sh).
That script downloads exact upstream archives, verifies pinned SHA-256 digests, applies the tracked
Momoding patch, and packages the resulting runtime into the debug APK.

| Component | Version | License | Upstream |
| --- | --- | --- | --- |
| PRoot | 5.1.107.86 | GPL-2.0-only | https://github.com/termux/proot |
| talloc | 2.4.3 | LGPL-3.0-or-later | https://talloc.samba.org/ |
| Alpine minirootfs | 3.23.5 aarch64 | Aggregate; packages retain individual licenses | https://www.alpinelinux.org/ |

`third_party/patches/proot-5.1.107.86-android-ndk.patch` is a modification to GPL-2.0-only PRoot
source and is distributed under the same GPL terms. The builder, patch, exact upstream version,
download URL, and source archive digest are included so the modified binary can be reproduced.

The Alpine root filesystem contains many independently licensed packages. Before distributing any
generated debug APK, preserve the exact upstream archives and build inputs, review the package
license inventory inside that root filesystem, and provide all corresponding source and notices
required for that exact artifact. The public source repository does not track the downloaded
archives, generated PRoot binaries, or Alpine root filesystem.

## Build and test tooling

Android Gradle Plugin, Kotlin tooling, KSP, TypeScript, Gradle, JUnit, Robolectric, esbuild, and
Android test libraries are build/test dependencies and are not Momoding-authored software. Their
versions are pinned in lockfiles and version catalogs.

Standard license texts used by the listed components are provided under [`licenses/`](licenses/).
Upstream license and NOTICE files control if this summary differs from a specific dependency.
