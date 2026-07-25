# Momoding wire contracts

This directory defines the transport boundary shared by the Android client and a compatible
remote host.

- `schemas/` contains TypeScript definitions for envelopes, snapshots, transfers, reliability,
  errors, commands, and Android-owned tools.
- `fixtures/pi-0.80.6/` contains deterministic synthetic protocol examples and byte oracles.
- `kotlin-contract/` contains strict Kotlin/JVM decoders, encoders, replay protection, chunk
  assembly, and the executable contract tests consumed by the Android build.

The repository does not include a remote-host service. These schemas and fixtures describe the
client boundary; they are not recordings of production sessions or claims that a host deployment
is available.

Run the Kotlin contract suite from the repository root:

```bash
./wire/kotlin-contract/gradlew -p wire/kotlin-contract --no-daemon test
```

Changes to a schema must include the corresponding Kotlin implementation, synthetic fixture, and
contract-test update when that boundary is affected.
