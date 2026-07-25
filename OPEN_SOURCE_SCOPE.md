# Open-source scope

Momoding is published as a clean, allowlisted source snapshot. The private development repository
and its historical validation material are not publication inputs.

## Included

- Android application source, resources, Room schemas, unit tests, and instrumentation tests
- Phone-local Pi runtime source, pinned lockfile, generated bundle, and reproducibility checks
- Shared wire schemas, Kotlin contract implementation, and synthetic fixtures
- Community, security, license, CI, and release-readiness files

## Excluded

- Private remote-host services and deployment configuration
- Experimental PRoot/Alpine binaries, root filesystems, downloads, and build caches
- Real device serials, hostnames, LAN addresses, local absolute paths, and ADB captures
- API keys, TLS keys, signing material, local environment files, and diagnostics archives
- Internal research, product plans, review transcripts, screenshots, validation reports, and logs
- The private repository's pre-public Git history

## Public release gates

A candidate is publishable only when:

1. `./scripts/verify.sh` passes from a fresh clone without a real provider key.
2. `./scripts/check-public-repo.sh` finds no forbidden path, credential, identity, or artifact.
3. Android unit tests and lint pass, and both debug and release APKs assemble.
4. The Pi runtime reproduces byte-for-byte without changing the checkout.
5. The release APK exposes only documented capabilities and contains required third-party notices.
6. GitHub private vulnerability reporting, secret scanning, push protection, and Dependabot are
   enabled before the repository is announced.

Signed production distribution, Play Store readiness, and the experimental Linux command runtime
are separate future gates.
