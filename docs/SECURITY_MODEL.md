# Security model

This document describes the current developer-preview design. It is not a formal proof, audit, or
guarantee.

## Protected assets

- OpenRouter API keys and provider configuration
- User-selected project files, shared-storage access, and persisted Android grants
- Attachments, camera captures, screen captures, prepared file changes, and diagnostics
- Task prompts, model responses, Pi session state, plans, goals, skills, and child-agent results
- Pairing credentials and pinned remote-host identity when the optional client path is used

## Trust boundaries

- Android and the app sandbox are trusted to enforce app storage, Keystore, URI grants, permission
  state, MediaProjection consent, accessibility enablement, and Shizuku binding state.
- Model output, project contents, shared intents, imported skills, provider responses, captured UI
  content, and remote network input are untrusted.
- OpenRouter necessarily receives prompts and content authorized for a model request. Momoding
  cannot provide end-to-end confidentiality from the selected model provider.
- PRoot translates paths and process behavior for a phone-local Linux environment. It is not a
  security boundary for hostile code.
- Shizuku grants elevated Android API access after a separate user-controlled setup. Momoding's
  current model tools expose only bounded package listing and package inspection, but the
  dependency and permission remain security-sensitive.

## Primary controls

| Risk | Current control |
| --- | --- |
| Credential disclosure | Keystore-backed AES-GCM envelope, no-backup storage, no credential passed into JS |
| Project filesystem scope | User-selected SAF roots, task-scoped identities, and current-grant checks |
| Shared-storage scope | Separate all-files access setting plus live capability reporting |
| Unauthorized content read | Bounded tool schemas, task context, and Android-owned execution |
| Unauthorized file write | Private preparation, exact diff, live preconditions, policy checks, and confirmation |
| Screen overcollection | User-started MediaProjection session; images are live for one tool turn |
| UI overreach | Accessibility must be enabled; actions require fresh snapshot-specific opaque handles |
| Package mutation | Shizuku tools are limited to read-only list and exact-package inspection operations |
| Prompt/tool confusion | Android owns policy and execution; tool requests are strictly decoded and allowlisted |
| Duplicate/replayed side effect | Durable operation identity, terminal proof, replay protection, and fail-closed recovery |
| Process death during a write | Durable journal plus interrupted-commit repair or cancellation |
| Oversized or hostile input | Bounded attachments, strict decoding, and chunk/page limits |
| Exported component abuse | Narrow share intents, protected accessibility/Shizuku components, non-exported providers |
| Dependency substitution | npm integrity, Gradle locks, Gradle verification metadata, SHA-pinned Actions and downloads |
| Accidental publication | Clean source commit, allowlisted export, tracked-path/secret scans, and fresh-clone gates |

## Android permissions and services

The main manifest declares network access, media metadata permissions, foreground MediaProjection
service permissions, all-files access, and the Shizuku manager permission. The app also declares an
accessibility service and a Shizuku provider. These are powerful capabilities and must be described
as opt-in user authority; they must not be presented as blanket device access.

`MANAGE_EXTERNAL_STORAGE` may be incompatible with some distribution channels or store policies.
Its presence is a product and release-policy decision, not something the open-source exporter
silently removes.

## User-visible authority

The application must not claim that a provider, folder, shared-storage root, screen session,
accessibility service, Shizuku service, terminal, or test runner is available unless current
runtime and Android state prove it. A model response is not evidence that a side effect happened;
the Android terminal record is authoritative.

## Known limitations

- Developer preview; no supported production release line or independent security audit.
- PRoot is not a hostile-code sandbox, and project commands can process untrusted repository input.
- A malicious or compromised model provider can observe authorized content and return adversarial
  output; Android tool boundaries reduce but do not eliminate that risk.
- Accessibility, screen capture, Shizuku, and all-files access materially increase the impact of a
  defect or compromised dependency.
- Release signing, Play Integrity, update distribution, store policy, native binary corresponding
  source, and Alpine package-license review are separate distribution gates.

Report suspected vulnerabilities privately according to [`SECURITY.md`](../SECURITY.md). Do not
include real credentials or private project content.
