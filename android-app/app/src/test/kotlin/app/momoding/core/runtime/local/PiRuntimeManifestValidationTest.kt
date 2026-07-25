package app.momoding.core.runtime.local

import org.junit.Assert.assertThrows
import org.junit.Test

class PiRuntimeManifestValidationTest {
    @Test
    fun acceptsExactPinnedRuntimeManifest() {
        validatePiRuntimeManifest(validManifest())
    }

    @Test
    fun rejectsPiVersionMismatch() {
        assertThrows(IllegalStateException::class.java) {
            validatePiRuntimeManifest(validManifest().copy(piVersion = "0.80.7"))
        }
    }

    @Test
    fun rejectsUnreviewedCompatibilityTransform() {
        assertThrows(IllegalStateException::class.java) {
            validatePiRuntimeManifest(
                validManifest().copy(
                    compatibilityTransforms = listOf("unreviewed-runtime-rewrite"),
                ),
            )
        }
    }

    private fun validManifest() = PiRuntimeAssetManifest(
        schemaVersion = 1,
        runtime = "earendil-works/pi AgentHarness",
        piVersion = "0.80.6",
        bundleFile = "pi-mobile.js",
        bundleSha256 = "a".repeat(64),
        sourceSha256 = "b".repeat(64),
        buildRevision = "c".repeat(40),
        buildTarget = "es2020",
        compatibilityTransforms = listOf(
            "pi-agent-core-compat-import:narrow-browser-shim",
            "pi-tool-validation:fail-closed-json-schema-subset",
        ),
    )
}
