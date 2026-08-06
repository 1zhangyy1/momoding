# Releasing Momoding

Momoding is currently a developer preview. GitHub prereleases may include a signed Core APK for
direct installation. Debug and unsigned release APKs remain local verification artifacts; this is
not a Play Store release.

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

1. Update `CHANGELOG.md`, user-facing documentation, and the single release version source
   `version.properties`. Android packaging and APK verification read the code/name from that file.
2. Review every dependency and lockfile change. Regenerate Gradle verification metadata only for
   intentional, reviewed updates.
3. Recheck `THIRD_PARTY_NOTICES.md` and bundled license/NOTICE files.
4. Run `./scripts/verify.sh` without provider credentials.
5. Clone the exact candidate commit into a new directory and run the same command again.
6. Confirm the release APK manifest, exported components, permissions, native libraries, assets,
   source revision, and runtime digest match the reviewed boundary.
7. Create an annotated version tag only after required CI succeeds.

## Binary distribution

The release signing key must stay outside this repository and CI. Its password must stay in a
credential manager rather than a shell script, Gradle property, or GitHub Actions log. Losing the
key prevents compatible upgrades, so the keystore and credential require separate encrypted
backups.

The expected Momoding release certificate SHA-256 digest is tracked in
[`android-app/release-signing-certificate.sha256`](android-app/release-signing-certificate.sha256).
For each signed GitHub prerelease:

1. Wait for required CI to pass on the exact public commit.
2. Build `app-release-unsigned.apk` in a fresh clone of that commit.
3. Run `zipalign`, then sign with `apksigner` using the protected offline key. Never print or pass
   the password on a command line recorded by CI.
4. Generate `<apk>.sha256` with the APK filename included.
5. Run `node scripts/verify-signed-release-apk.mjs <apk> <apk>.sha256`. This verifies the reviewed
   package/version/capability boundary, APK Signature Scheme v3, the pinned certificate, and the
   checksum.
6. Fresh-install the exact APK on a supported physical device, launch it, and verify the package,
   version, signer, and core setup/task flow.
7. For updater-enabled releases, install the candidate over the previous signed release and verify
   that Android preserves the application identity and data. The first updater-enabled release
   still requires manual installation because its predecessor cannot discover it in-app.
8. Create the annotated tag and GitHub prerelease only after the signed-artifact review passes.

A signed Core APK still requires:

- final ABI/device coverage and upgrade/migration tests;
- a documented rollback decision if the physical-device smoke test fails.

Store distribution would additionally require its own policy, privacy disclosure, data-safety,
account, signing, and update-path review; those are outside this GitHub developer preview.

Do not publish a PRoot/Alpine-enabled binary until the license inventory, complete corresponding
source, notices, update, ABI, and security gates in [`OPEN_SOURCE_SCOPE.md`](OPEN_SOURCE_SCOPE.md)
are met. PRoot is not a hostile-code sandbox.
