# Releasing Momoding

Momoding is currently a developer preview. This repository can publish source snapshots; its
locally built debug and unsigned release APKs are verification artifacts, not approved production
or Play Store releases.

## Initial public repository

1. Create the public repository from this allowlisted tree, not from the private development
   history.
2. Use a deliberately chosen public Git author name and privacy-safe email.
3. Keep the initial source snapshot separate from the generated Pi runtime bundle so the bundle
   manifest can point to the exact public source commit.
4. Run `./scripts/verify.sh` from a new `git clone --no-local` checkout.
5. Confirm the checkout stays clean and inspect the two initial commits before pushing.
6. Enable branch protection or repository rules, required CI, private vulnerability reporting,
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

Do not publish a PRoot/Alpine-enabled binary until the additional license, complete-source,
sandbox, update, ABI, and security gates in [`OPEN_SOURCE_SCOPE.md`](OPEN_SOURCE_SCOPE.md) are met.
