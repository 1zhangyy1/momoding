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
- Android-owned tools list authorized metadata, request content-read consent, prepare file changes,
  commit confirmed changes, read explicit attachments, and list bounded photo metadata.

Model output is never treated as Android authority. The model can request a tool; Android policy,
durable state, current permission state, and user decisions determine whether it runs.

## Phone-local agent runtime

`mobile-runtime-js/` builds the pinned Pi `0.80.6` agent harness into one QuickJS-compatible
JavaScript asset. Android owns network credentials and HTTP. QuickJS receives provider responses
through a native mailbox, so provider API keys and Authorization headers do not enter JavaScript.

The tracked runtime asset is generated deterministically. Its manifest records the Pi version,
source digest, bundle digest, compatibility transforms, and the public source revision used to
build it.

The runtime contains optional command-tool implementation scaffolding for future development, but
the public Android bridge passes `projectToolsEnabled=false`, the JavaScript API defaults that
flag to `false`, and the release APK contains no PRoot executable or Alpine filesystem.

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
| Terminal command | Unavailable in the public build |

## Process-death and replay behavior

Long-lived user decisions are recorded before execution or delivery. Runtime restoration validates
the task/session identity and reconstructs state without replaying provider or tool work. Pending
side effects either repair from durable state or fail closed; an internal replay is not treated as
permission to repeat a user-visible action.

## Build outputs

`scripts/verify.sh` verifies sources, dependency checksums, the reproducible runtime, protocol
contracts, Android unit/instrumentation compilation, lint, APK assembly, the merged release
manifest, exported components, and packaged artifacts. Build products, signing material, captures,
and validation reports are intentionally not tracked.
