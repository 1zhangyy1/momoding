# Kotlin wire contract

This independent Kotlin/JVM module contains strict transport decoders, client-frame encoders,
chunk/snapshot assembly, replay protection, and reliability projection logic shared by the Android
code. Pi messages and tool payloads remain raw JSON at this boundary.

Run the complete contract suite with JDK 17:

```bash
./wire/kotlin-contract/gradlew -p wire/kotlin-contract test
```

The fixtures under `wire/fixtures/pi-0.80.6/` are synthetic protocol fixtures. They contain no
production sessions, credentials, device identifiers, or user project content.
