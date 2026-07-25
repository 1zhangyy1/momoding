# Security model

This document describes the current developer-preview design. It is not a formal proof, audit, or
guarantee.

## Protected assets

- OpenRouter API keys and provider configuration
- User-selected project files and persisted Android SAF grants
- Attachments, camera captures, prepared file changes, and diagnostics
- Task prompts, model responses, Pi session state, goals, and child-agent results
- Pairing credentials and pinned remote-host identity when that optional client path is used

## Trust boundaries

- The Android operating system and app sandbox are trusted to enforce app storage, Keystore, URI
  grants, and permission state.
- Model output, project contents, shared intents, imported skills, provider responses, and remote
  network input are untrusted data.
- OpenRouter necessarily receives prompts and any content the user authorizes for a model request.
  Momoding cannot provide end-to-end confidentiality from the selected model provider.
- PRoot path translation is not a hostile-code sandbox. The public build therefore exposes no
  command tools and packages no PRoot or Alpine artifacts.

## Primary controls

| Risk | Current control |
| --- | --- |
| Credential disclosure | Keystore-backed AES-GCM envelope, no-backup storage, no credential in JS or APK |
| Broad filesystem access | User-selected SAF folders; no full-device filesystem claim |
| Unauthorized content read | Separate task-scoped approval and current-grant validation |
| Unauthorized file write | Private preparation, exact diff, live preconditions, explicit commit confirmation |
| Path or URI leakage to the model | Opaque task-scoped identities and sanitized presentation |
| Prompt/tool confusion | Android owns policy and execution; tool requests are strictly decoded and allowlisted |
| Duplicate/replayed side effect | Durable operation identity, terminal proof, replay protection, and fail-closed recovery |
| Process death during a write | Durable journal plus interrupted-commit repair or cancellation |
| Oversized or hostile input | Bounded attachments, strict JSON/schema decoding, chunk/page limits |
| Exported Android component abuse | Narrow share intents, non-exported providers/services, release-manifest allowlist |
| Dependency substitution | npm integrity, Gradle locks, Gradle SHA-256 verification metadata, pinned Action commits |
| Accidental publication | Allowlisted clean history plus tracked-path, identity, secret, and APK-content gates |

## User-visible authority

The application must not claim that a provider, host, folder, photo capability, terminal, or test
runner is available unless the current runtime and Android permission state prove it. File
inspection and mutation are separate authorities. A successful model response is not evidence that
a side effect happened; the Android terminal record is.

## Known limitations

- This is a developer preview with no supported production release line.
- The repository has not received an independent security audit.
- The remote-host service and its deployment controls are outside this repository.
- A malicious or compromised model provider can observe authorized prompt content and return
  adversarial output; Android approval and tool boundaries reduce but do not eliminate that risk.
- A future command runtime requires a separate sandbox, licensing, update, ABI, and device review.
- Release signing, Play Integrity, update distribution, and store policy validation are future
  distribution gates.

Suspected vulnerabilities must be reported privately according to
[`SECURITY.md`](../SECURITY.md). Do not include real credentials or private project content.
