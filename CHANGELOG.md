# Changelog

All notable public changes will be documented here. The project follows
[Semantic Versioning](https://semver.org/) after the first tagged release.

## Unreleased

## 0.1.0-alpha.3 - 2026-08-06

### Added

- Trusted in-app update checks from GitHub Releases, available from `Settings → About Momoding`.
- A compact task-home update notice with verified APK download progress and retry state.

### Changed

- The public Compose application entry point now consistently uses the Momoding name.

### Security

- Downloaded updates must match the expected checksum, application ID, version, and current signing
  certificate before Android's package installer is opened.
- Momoding cannot install silently: Android still requires install-source trust and explicit user
  confirmation.

## 0.1.0-alpha.2 - 2026-08-06

### Added

- Capability-aware Provider setup with OpenRouter web search, bounded web fetch, and separate image
  generation.
- Optional Codex device authorization and task-scoped Provider selection.
- Full-screen generated-image preview with copy and download actions.

### Changed

- Tool activity now stays as a compact, expandable one-line timeline entry.
- Refined the Momoding visual system while preserving the original character artwork and classic
  conversation avatar.
- Release version metadata now has one source shared by Android packaging and APK verification.

### Security

- Provider credentials and OAuth tokens remain in the Android native vault and are not exposed to
  the bundled JavaScript runtime.
- Search and fetched page content remain bounded before entering the task context.

## 0.1.0-alpha.1 - 2026-08-03

### Added

- Clean Momoding open-source source snapshot with new public Git history.
- Android local-first agent workspace, encrypted OpenRouter credential storage, SAF file review
  flow, attachments, skills, plans, goals, child agents, and recovery state.
- Phone-local project commands, shared-storage tools, screen capture, accessibility UI actions, and
  read-only Shizuku package inspection.
- Reproducible phone-local Pi runtime verification.
- CI, security policy, contribution guide, public-scope gate, and third-party notices.

### Security

- Remote-host service and internal development evidence remain outside the public boundary.
- Powerful Android capabilities and the non-sandboxed PRoot runtime are documented explicitly.
- Public snapshots are generated from the private source-of-truth repository by allowlist.
