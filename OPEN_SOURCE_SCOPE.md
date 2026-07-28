# Open-source scope

The private Momoding repository is the source of truth. The public repository is a deterministic,
allowlisted projection of a reviewed private commit; it is not a separately maintained product
fork.

## Same in private and public

- Android production source, resources, Room schemas, and selected reviewable tests
- Phone-local Pi runtime source, lockfile, generated bundle, and reproducibility checks
- Shared wire schemas, Kotlin contract implementation, and synthetic fixtures
- Build files required by the exported source, including the pinned phone-local Linux runtime
  builder and the Momoding PRoot patch
- Public README, architecture, security, contribution, license, CI, and release-readiness files

The exporter copies these paths byte-for-byte. It must not patch features, permissions, tool
availability, package names, or runtime behavior for the public repository.

## Private only

- Remote-host services, deployment configuration, and local orchestration
- Internal research, product plans, review transcripts, screenshots, validation reports, and logs
- Real device serials, hostnames, LAN addresses, local absolute paths, ADB captures, and diagnostics
- API keys, TLS keys, signing material, local environment files, caches, and generated APKs
- Experimental harnesses and fixtures that are not required to build the exported application
- The private repository's pre-public Git history

## What “in sync” means

The shared allowlisted files must be byte-identical to the selected private commit. The two
repositories intentionally have different Git histories and the private repository contains
additional non-public material. A drift check compares the public worktree against a fresh export;
manual edits in the public repository are expected to fail that check.

Each export writes `.public-source.json`. Its `sourceRevision` identifies the reviewed private
commit used to build the checked-in Pi runtime, while `exportRevision` identifies the later clean
private commit from which the full public snapshot was copied. Keeping these separate makes the
generated runtime reproducible even though the public repository has an independent Git history.

## Public release gates

A candidate is publishable only when:

1. It was produced by `./scripts/export-public-repo.sh` from a clean private commit.
2. `./scripts/check-public-repo.sh` finds no forbidden path, credential, identity, or artifact.
3. Runtime, Kotlin, Android unit, lint, and assemble checks pass from a fresh public clone.
4. The Pi bundle reproduces byte-for-byte without changing the checkout.
5. The release APK exposes only documented permissions and components.
6. Third-party notices match every bundled or build-fetched component.
7. GitHub private vulnerability reporting, secret scanning, push protection, and Dependabot are
   enabled before announcement.

Signed production distribution and store readiness remain separate release gates. In particular,
do not publish generated PRoot/Alpine APK artifacts until corresponding-source and package-license
obligations have been reviewed for that exact binary.
