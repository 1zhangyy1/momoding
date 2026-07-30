# Architecture

Momoding is an Android-first agent workspace with three explicit layers:

```text
Compose UI and coordinators
        |
Android state, policy, and tool executors
        |
QuickJS Pi agent runtime ---- OpenRouter API
        |
synthetic wire contracts for an optional remote host
```

The phone-local path is the public build's primary path. A compatible remote-host service may use
the wire contracts, but that service is not included in this repository and is not required for
the phone-local agent loop.

## Android application

`android-app/` owns the product UI and every Android capability boundary.

- Compose screens and view models present tasks, plans, goals, approvals, files, outputs, provider
  setup, settings, and extensions.
- Room is the durable source of truth for task projections, attention requests, authorized-folder
  identities, prepared file changes, skills, goals, child agents, and recovery state.
- Provider credentials are encrypted with an Android Keystore-backed AES-GCM key and stored in the
  app's no-backup directory.
- Android Storage Access Framework grants are the source of truth for project-folder access.
- Android-owned tools handle project commands, authorized files, shared storage, attachments,
  calendar, contacts, foreground location, clipboard, Momoding-owned notifications, photo metadata
  and mutations, screen capture, accessibility UI actions, and read-only Shizuku package facts.

Model output is never treated as Android authority. The model can request a tool; Android policy,
durable state, current permission state, and user decisions determine whether it runs.

## Phone-local agent runtime

`mobile-runtime-js/` builds the pinned Pi `0.80.6` agent harness into one QuickJS-compatible
JavaScript asset. Android owns network credentials and HTTP. QuickJS receives provider responses
through a native mailbox, so provider API keys and Authorization headers do not enter JavaScript.

The tracked runtime asset is generated deterministically. Its manifest records the Pi version,
source digest, bundle digest, compatibility transforms, and the public source revision used to
build it.

Task sessions expose project command and test tools. Android executes them through a phone-local
PRoot/Alpine project environment in supported debug builds, then routes any prepared real-folder
change through the same Android policy and confirmation path. PRoot is a compatibility layer, not
a hostile-code sandbox.

Screen images are injected only into the live provider turn that requested them. UI actions require
a fresh accessibility snapshot and snapshot-specific opaque node handle. Shizuku integration is
limited to bounded installed-package listing and exact-package inspection.

Calendar, contacts, notifications, and media mutations are split into prepare and execute stages.
Android permissions and tool approval are separate decisions; execution uses opaque handles, live
conflict checks, bounded result payloads, and post-operation verification. Clipboard and current
location are foreground-gated, and Momoding does not request background location or notification
listener access.

## Wire contracts

`wire/` defines a strict client boundary for task snapshots, event envelopes, reliability,
pagination, chunking, errors, and Android attention tools.

The fixtures are deterministic synthetic examples. They are schema and byte-domain oracles, not
captured sessions or production evidence. The independent Kotlin/JVM module validates decoding,
encoding, replay protection, ordering, and reconstruction before the Android application consumes
the same implementation through an included Gradle build.

## State and authority

| State or action | Authority |
| --- | --- |
| Provider credential | Android encrypted vault |
| Authorized folder | Android persisted SAF grant plus Room identity |
| File-content read | Task-scoped request and explicit policy/user decision |
| File write | Prepared change set, reviewed diff, live preconditions, user confirmation |
| Agent conversation | Pi session plus Android task projection |
| Goal and child-agent recovery | Room plus validated Pi events/snapshots |
| Remote-host connection | Android pairing state and pinned endpoint; service not included |
| Terminal command | Android project-tool executor plus PRoot/Alpine debug runtime |
| Screen capture | User-started MediaProjection session and turn-local image |
| UI action | Enabled accessibility service plus fresh snapshot handle |
| Package fact | Authorized Shizuku session and read-only bounded tool |
| Calendar/contact mutation | Android provider plus current permissions, approved plan, live preconditions, and post-verification |
| Current location | Foreground Android location provider plus requested coarse/precise permission |
| Clipboard | Foreground Android clipboard plus sensitive-content and output bounds |
| Agent notification | Momoding-owned channel and opaque notification identity |
| Photo mutation | Current MediaStore scope, approved plan, Android system consent, and post-verification |

## Process-death and replay behavior

Long-lived user decisions are recorded before execution or delivery. Runtime restoration validates
the task/session identity and reconstructs state without replaying provider or tool work. Pending
side effects either repair from durable state or fail closed; an internal replay is not treated as
permission to repeat a user-visible action.

## Build outputs

`scripts/verify.sh` verifies sources, dependency checksums, the reproducible runtime, protocol
contracts, Android unit/instrumentation compilation, lint, APK assembly, the merged release
manifest, exported components, and packaged artifacts. The debug build fetches pinned PRoot,
talloc, and Alpine inputs; generated binaries and root filesystems are not tracked. Build products,
signing material, captures, and validation reports are intentionally excluded from publication.
