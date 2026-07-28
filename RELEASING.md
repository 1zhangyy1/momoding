# Releasing Momoding

Momoding is currently a developer preview. This repository can publish source snapshots; its
locally built debug and unsigned release APKs are verification artifacts, not approved production
or Play Store releases.

## Initial public repository

1. Create the public repository with `./scripts/export-public-repo.sh` from a clean reviewed private
   commit; never copy the private development history.
2. Use a deliberately chosen public Git author name and privacy-safe email.
3. Confirm `./scripts/check-public-sync.sh` reports no drift against the generated public tree.
4. Rebuild the generated Pi runtime bundle against the selected source revision.
5. Run `./scripts/verify.sh` from a new `git clone --no-local` checkout.
6. Confirm the checkout stays clean and inspect every public commit before pushing.
7. Enable branch protection or repository rules, required CI, private vulnerability reporting,
   secret scanning, push protection, and Dependabot before announcing the repository.

## Source release checklist

1. Update `CHANGELOG.md`, version code/name, and user-facing documentation.
2. Review every dependency and lockfile change. Regenerate Gradle verification metadata only for
   intentional, reviewed updates.
3. Recheck `THIRD_PARTY_NOTICES.md` and bundled license/NOTICE files.
4. Run `./scripts/verify.sh` without provider credentials.
5. Clone the exact candidate commit into a new directory and run the same command again.
6. Confirm the release APK manifest, exported components, permissions, native libraries, assets,
   source revision, and runtime digest match the reviewed boundary.
7. Create an annotated version tag only after required CI succeeds.

## Binary distribution

Debug and unsigned release APKs are development artifacts, not production releases. A future
signed release additionally requires:

- protected signing keys outside the repository and CI logs;
- reproducible, reviewed signing and provenance procedures;
- Android store policy, privacy disclosure, data-safety, account, and update-path review;
- final ABI/device coverage and upgrade/migration tests;
- release checksums and a documented rollback process.

Do not publish a PRoot/Alpine-enabled binary until the license inventory, complete corresponding
source, notices, update, ABI, and security gates in [`OPEN_SOURCE_SCOPE.md`](OPEN_SOURCE_SCOPE.md)
are met. PRoot is not a hostile-code sandbox.
