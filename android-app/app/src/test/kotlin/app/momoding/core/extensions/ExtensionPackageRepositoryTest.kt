package app.momoding.core.extensions

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.MomodingDatabase
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtensionPackageRepositoryTest {
    private lateinit var database: MomodingDatabase
    private lateinit var repository: ExtensionPackageRepository
    private val parser = ExtensionPackageManifestParser()
    private val clock = AtomicLong(100)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java).build()
        repository = ExtensionPackageRepository(
            database = database,
            nowMillis = { clock.getAndIncrement() },
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `strict manifest installs disabled enables verified snapshot and live gate`() = runTest {
        val fixture = fixturePackage(version = "1.0.0")
        val installed = repository.install(fixture.manifest, fixture.files)

        assertFalse(installed.enabled)
        assertEquals(2, installed.fileCount)
        assertTrue(repository.enabledPackageSet().packages.isEmpty())

        repository.setEnabled(fixture.manifest.snapshot.id, true)
        val enabled = repository.enabledPackageSet()
        assertEquals(listOf("com.example.device-helper"), enabled.packages.map { it.id })
        assertEquals(extensionPackageSetDigest(enabled.packages), enabled.digest)
        val authorized = repository.authorizeTool(
            packageId = fixture.manifest.snapshot.id,
            packageDigest = fixture.manifest.snapshot.packageDigest,
            toolName = "extension_calendar",
            type = "android-tool-alias",
            description = "Use the existing Calendar Tool.",
            targetTool = "device_calendar",
            prompt = null,
        )
        assertEquals("device_calendar", authorized.targetTool)

        repository.setEnabled(fixture.manifest.snapshot.id, false)
        assertEquals(
            "EXTENSION_PACKAGE_NOT_ENABLED",
            runCatching {
                repository.authorizeTool(
                    fixture.manifest.snapshot.id,
                    fixture.manifest.snapshot.packageDigest,
                    "extension_calendar",
                    "android-tool-alias",
                    "Use the existing Calendar Tool.",
                    "device_calendar",
                    null,
                )
            }.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `same id update is atomic and requires explicit re-enable`() = runTest {
        val first = fixturePackage(version = "1.0.0")
        repository.install(first.manifest, first.files)
        repository.setEnabled(first.manifest.snapshot.id, true)
        val second = fixturePackage(version = "1.1.0", readme = "updated")

        val updated = repository.install(second.manifest, second.files)

        assertEquals("1.1.0", updated.manifest.snapshot.version)
        assertFalse(updated.enabled)
        assertEquals(100L, updated.createdAtMillis)
        assertTrue(updated.updatedAtMillis > updated.createdAtMillis)
        assertEquals(second.manifest.snapshot.packageDigest, updated.manifest.snapshot.packageDigest)
    }

    @Test
    fun `update and delete clear package credentials after the durable lifecycle change`() = runTest {
        val cleared = mutableListOf<String>()
        val lifecycleRepository = ExtensionPackageRepository(
            database = database,
            nowMillis = { clock.getAndIncrement() },
            clearPackageCredentials = { cleared += it },
        )
        val first = fixturePackage(version = "1.0.0")
        lifecycleRepository.install(first.manifest, first.files)
        lifecycleRepository.install(first.manifest, first.files)
        assertTrue(cleared.isEmpty())

        val second = fixturePackage(version = "1.1.0", readme = "updated")
        lifecycleRepository.install(second.manifest, second.files)
        assertEquals(listOf(first.manifest.snapshot.id), cleared)

        assertTrue(lifecycleRepository.delete(first.manifest.snapshot.id))
        assertEquals(listOf(first.manifest.snapshot.id, first.manifest.snapshot.id), cleared)
    }

    @Test
    fun `credential cleanup failure leaves the committed update installed and disabled`() = runTest {
        val lifecycleRepository = ExtensionPackageRepository(
            database = database,
            nowMillis = { clock.getAndIncrement() },
            clearPackageCredentials = { error("vault cleanup unavailable") },
        )
        val first = fixturePackage(version = "1.0.0")
        lifecycleRepository.install(first.manifest, first.files)
        lifecycleRepository.setEnabled(first.manifest.snapshot.id, true)
        val second = fixturePackage(version = "1.1.0", readme = "updated")

        assertEquals(
            "vault cleanup unavailable",
            runCatching { lifecycleRepository.install(second.manifest, second.files) }
                .exceptionOrNull()?.message,
        )

        val committed = lifecycleRepository.packageRecords().single()
        assertEquals("1.1.0", committed.manifest.snapshot.version)
        assertFalse(committed.enabled)
    }

    @Test
    fun `disable remains available when stored package bytes fail integrity`() = runTest {
        val fixture = fixturePackage(version = "1.0.0")
        repository.install(fixture.manifest, fixture.files)
        repository.setEnabled(fixture.manifest.snapshot.id, true)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE extension_package_files SET content = X'00' " +
                "WHERE packageId = ? AND relativePath = ?",
            arrayOf(fixture.manifest.snapshot.id, "README.md"),
        )

        val disabled = repository.setEnabled(fixture.manifest.snapshot.id, false)

        assertFalse(disabled.enabled)
        assertEquals(
            "EXTENSION_PACKAGE_NOT_ENABLED",
            runCatching {
                repository.authorizeTool(
                    fixture.manifest.snapshot.id,
                    fixture.manifest.snapshot.packageDigest,
                    "extension_calendar",
                    "android-tool-alias",
                    "Use the existing Calendar Tool.",
                    "device_calendar",
                    null,
                )
            }.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `parser rejects unknown fields missing capability and digest tamper`() {
        val fixture = fixturePackage(version = "1.0.0")
        val manifestFile = fixture.files.single { it.relativePath == EXTENSION_MANIFEST_PATH }
        val unknown = manifestFile.content.toString(Charsets.UTF_8).dropLast(1) + ",\"unknown\":true}"
        assertEquals(
            "EXTENSION_PACKAGE_MANIFEST_FIELDS_INVALID",
            runCatching { parser.parse(unknown, fixture.files.withManifest(unknown)) }
                .exceptionOrNull()?.message,
        )

        assertEquals(
            "EXTENSION_PACKAGE_CAPABILITY_UNDECLARED",
            runCatching { fixturePackage(version = "1.0.1", requiredCapabilities = emptyList()) }
                .exceptionOrNull()?.message,
        )

        val tamperedFiles = fixture.files.map { file ->
            if (file.relativePath == "README.md") file.copy(
                content = "tampered".toByteArray(),
                contentSha256 = "tampered".toByteArray().sha256(),
            ) else file
        }
        assertEquals(
            "EXTENSION_PACKAGE_DIGEST_MISMATCH",
            runCatching { parser.parse(fixture.rawManifest, tamperedFiles) }
                .exceptionOrNull()?.message,
        )
    }

    @Test
    fun `strict parser rejects stringly typed numbers and numeric strings`() {
        val fixture = fixturePackage(version = "1.0.0")
        val stringSchema = fixture.rawManifest.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")
        val numericName = fixture.rawManifest.replace("\"name\":\"Device helper\"", "\"name\":7")
        val numericCapability = fixture.rawManifest.replace("\"calendar\"", "7")

        listOf(stringSchema, numericName, numericCapability).forEach { invalid ->
            assertEquals(
                "EXTENSION_PACKAGE_MANIFEST_FIELD_INVALID",
                runCatching { parser.parse(invalid, fixture.files.withManifest(invalid)) }
                    .exceptionOrNull()?.message,
            )
        }
    }

    @Test
    fun `live authorization binds every stored declaration field`() = runTest {
        val fixture = fixturePackage(version = "1.0.0")
        repository.install(fixture.manifest, fixture.files)
        repository.setEnabled(fixture.manifest.snapshot.id, true)

        val authorized = repository.authorizeTool(
            packageId = fixture.manifest.snapshot.id,
            packageDigest = fixture.manifest.snapshot.packageDigest,
            toolName = "extension_checklist",
            type = "prompt-tool",
            description = "Return a fixed checklist.",
            targetTool = null,
            prompt = "Check live state before reporting success.",
        )
        assertEquals("Check live state before reporting success.", authorized.prompt)
        assertEquals(
            "EXTENSION_PACKAGE_TOOL_NOT_AUTHORIZED",
            runCatching {
                repository.authorizeTool(
                    packageId = fixture.manifest.snapshot.id,
                    packageDigest = fixture.manifest.snapshot.packageDigest,
                    toolName = "extension_checklist",
                    type = "prompt-tool",
                    description = "Return a fixed checklist.",
                    targetTool = null,
                    prompt = "Tampered prompt",
                )
            }.exceptionOrNull()?.message,
        )
        assertEquals(
            "EXTENSION_PACKAGE_TOOL_NOT_AUTHORIZED",
            runCatching {
                repository.authorizeTool(
                    packageId = fixture.manifest.snapshot.id,
                    packageDigest = fixture.manifest.snapshot.packageDigest,
                    toolName = "extension_calendar",
                    type = "android-tool-alias",
                    description = "Tampered description",
                    targetTool = "device_calendar",
                    prompt = null,
                )
            }.exceptionOrNull()?.message,
        )
        val connector = repository.authorizeTool(
            packageId = fixture.manifest.snapshot.id,
            packageDigest = fixture.manifest.snapshot.packageDigest,
            toolName = "extension_connector",
            type = "connector-proxy",
            description = "Use the task Connector.",
            targetTool = null,
            prompt = null,
        )
        assertEquals("connector-proxy", connector.type)
        assertEquals(
            "EXTENSION_PACKAGE_TOOL_NOT_AUTHORIZED",
            runCatching {
                repository.authorizeTool(
                    packageId = fixture.manifest.snapshot.id,
                    packageDigest = fixture.manifest.snapshot.packageDigest,
                    toolName = "extension_connector",
                    type = "connector-proxy",
                    description = "Tampered description",
                    targetTool = null,
                    prompt = null,
                )
            }.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `validation fixture digest stays reproducible`() {
        val readme = extensionFile("README.md", "PXP-6B emulator fixture.\n")
        val manifest = buildJsonObject {
            put("schemaVersion", 1)
            put("id", "com.momoding.fixture.device-helper")
            put("name", "Device helper fixture")
            put("version", "1.0.0")
            put("description", "PXP-6B emulator gate package.")
            put("runtime", "declarative-v1")
            put("entrypoint", kotlinx.serialization.json.JsonNull)
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "android-tool-alias")
                    put("name", "fixture_device_status")
                    put("description", "Read live Android capability state.")
                    put("targetTool", "device_capabilities_get")
                })
                add(buildJsonObject {
                    put("type", "prompt-tool")
                    put("name", "fixture_checklist")
                    put("description", "Return the fixed emulator gate checklist.")
                    put("prompt", "Report exactly: PXP6B_EXTENSION_OK")
                })
            })
            put("requiredCapabilities", buildJsonArray { })
            put("optionalCapabilities", buildJsonArray { })
            put("networkOrigins", buildJsonArray { })
        }

        assertEquals(
            "460ec60379e740c3130bc4ddcda4d98d9d10c79dea7eb32af065f01de68d218c",
            extensionPackageDigest(manifest, listOf(readme)),
        )
    }

    private fun fixturePackage(
        version: String,
        readme: String = "fixture",
        requiredCapabilities: List<String> = listOf("calendar"),
    ): Fixture {
        val payloadFiles = listOf(extensionFile("README.md", readme))
        val base = buildJsonObject {
            put("schemaVersion", 1)
            put("id", "com.example.device-helper")
            put("name", "Device helper")
            put("version", version)
            put("description", "A bounded fixture Extension.")
            put("runtime", "declarative-v1")
            put("entrypoint", kotlinx.serialization.json.JsonNull)
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "android-tool-alias")
                    put("name", "extension_calendar")
                    put("description", "Use the existing Calendar Tool.")
                    put("targetTool", "device_calendar")
                })
                add(buildJsonObject {
                    put("type", "prompt-tool")
                    put("name", "extension_checklist")
                    put("description", "Return a fixed checklist.")
                    put("prompt", "Check live state before reporting success.")
                })
                add(buildJsonObject {
                    put("type", "connector-proxy")
                    put("name", "extension_connector")
                    put("description", "Use the task Connector.")
                })
            })
            put("requiredCapabilities", buildJsonArray {
                requiredCapabilities.forEach { add(JsonPrimitive(it)) }
            })
            put("optionalCapabilities", buildJsonArray { })
            put("networkOrigins", buildJsonArray { })
        }
        val digest = extensionPackageDigest(base, payloadFiles)
        val full = JsonObject(base + ("packageDigest" to JsonPrimitive(digest)))
        val raw = canonicalJson(full)
        val allFiles = (payloadFiles + extensionFile(EXTENSION_MANIFEST_PATH, raw, "application/json"))
            .sortedBy(ExtensionPackageFile::relativePath)
        return Fixture(parser.parse(raw, allFiles), allFiles, raw)
    }

    private fun extensionFile(
        path: String,
        content: String,
        mimeType: String = "text/markdown",
    ): ExtensionPackageFile {
        val bytes = content.toByteArray()
        return ExtensionPackageFile(path, mimeType, bytes, bytes.sha256())
    }

    private fun List<ExtensionPackageFile>.withManifest(raw: String): List<ExtensionPackageFile> =
        map { file ->
            if (file.relativePath == EXTENSION_MANIFEST_PATH) {
                extensionFile(EXTENSION_MANIFEST_PATH, raw, "application/json")
            } else file
        }

    private data class Fixture(
        val manifest: ExtensionPackageManifest,
        val files: List<ExtensionPackageFile>,
        val rawManifest: String,
    )
}
