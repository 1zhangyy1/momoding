# Changelog

All notable public changes will be documented here. The project follows
[Semantic Versioning](https://semver.org/) after the first tagged release.

## Unreleased

## 0.1.0-alpha.5 - 2026-08-13

### Added

- Installable Skill packages with bounded on-demand resources and task-scoped enablement.
- A reviewed mobile Pi Extension profile for declarative tools and asynchronous `registerTool`
  packages, including a deterministic source packer and compatibility diagnosis.
- Task-scoped Connector support and isolated Extension Workers with bounded Android Host Tool and
  HTTPS delegation.
- Compact Extension Tool Activity entries for running, completed, failed, and cancelled Host work.
- A unified `Skills & extensions` settings experience with one Add entry, plain-language access
  review, clear on/off states, credential setup, and recovery actions.
- Resumable in-app APK downloads that retain checksum-bound partial progress after interruptions.

### Changed

- Extension tools now reconcile with the current package set across ordinary tasks, Plan Mode,
  Goal Mode, process restore, package updates, disablement, and removal.
- Package updates remain disabled until the user reviews changed capabilities, Host Tools, HTTP
  origins, methods, and credential slots.
- Update failures now offer an explicit Retry action and reuse saved download progress when it is
  still valid for the same release.

### Security

- Extension code runs in a non-exported Android isolated process without direct network access;
  every Host call is re-authorized against the current package digest and declaration.
- Extension HTTP credentials remain in the Android Keystore-backed Vault and are injected only by
  the bounded native HTTPS client; secrets are not returned to the Worker.
- Stop, timeout, package revocation, and late-result paths fail closed without bypassing Android's
  permission, approval, or side-effect policy.

## 0.1.0-alpha.4 - 2026-08-07

### Fixed

- Prevented task detail from crashing when a web-search activity is interleaved with one streaming
  assistant message.

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
