# Third-party notices

Momoding is MIT licensed, but it depends on components that retain their own licenses. This file
summarizes direct, material dependencies of the source and Android runtime; transitive
dependencies remain governed by their upstream license metadata.

## Shipped or bundled at runtime

| Component | Version | License | Upstream |
| --- | --- | --- | --- |
| AndroidX, Jetpack Compose, Room, DataStore, Navigation, Lifecycle | pinned in `libs.versions.toml` | Apache-2.0 | https://github.com/androidx/androidx |
| Kotlin stdlib, coroutines, serialization | pinned in Gradle locks | Apache-2.0 | https://github.com/JetBrains/kotlin |
| OkHttp | 5.4.0 | Apache-2.0 | https://github.com/square/okhttp |
| quickjs-kt | 1.0.5 | Apache-2.0 | https://github.com/dokar3/quickjs-kt |
| QuickJS engine | bundled by quickjs-kt | MIT | https://bellard.org/quickjs/ |
| SQLite | bundled by AndroidX SQLite | Public domain | https://www.sqlite.org/copyright.html |
| Multiplatform Markdown Renderer | 0.38.1 | Apache-2.0; portions MIT | https://github.com/mikepenz/multiplatform-markdown-renderer |
| Pi Agent Core and Pi AI | 0.80.6 | MIT | https://github.com/earendil-works/pi |
| Phosphor Icons subset | 2.1 source family | MIT | https://github.com/phosphor-icons/core |

The Phosphor attribution and embedded MIT text are also available in
[`android-app/PHOSPHOR-NOTICE.md`](android-app/PHOSPHOR-NOTICE.md).

## Build and test tooling

Android Gradle Plugin, Kotlin tooling, KSP, TypeScript, Gradle, JUnit, Robolectric, esbuild, and
Android test libraries are build/test dependencies and are not presented as Momoding-authored
software. Their versions are pinned in lockfiles and version catalogs.

The repository retains optional Kotlin integration source for a PRoot/Alpine command runtime, but
the public build exposes no command tools and includes no PRoot binary or Alpine filesystem. Any
future binary distribution of that runtime requires a separate review of its licenses, complete
corresponding source, notices, update policy, and architecture support.

Standard license texts used by the listed components are provided under [`licenses/`](licenses/).
Upstream license and NOTICE files control if this summary differs from a particular dependency.
