# Momoding wire contracts

`wire/` contains the shared protocol schemas, deterministic synthetic fixtures, and the independent
Kotlin/JVM contract implementation used by the Android application.

The fixtures are schema and byte-domain oracles. They are not captured production sessions and do
not contain real credentials, device identities, or user project content.

Run the Kotlin contract tests:

```bash
JAVA_HOME=/path/to/jdk-17 \
./wire/kotlin-contract/gradlew -p wire/kotlin-contract test
```

The Android build consumes the same implementation through an included Gradle build.
