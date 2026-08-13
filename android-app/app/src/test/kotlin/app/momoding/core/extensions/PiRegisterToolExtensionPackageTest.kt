package app.momoding.core.extensions

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.MomodingDatabase
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
class PiRegisterToolExtensionPackageTest {
    private val parser = ExtensionPackageManifestParser()
    private lateinit var database: MomodingDatabase
    private lateinit var repository: ExtensionPackageRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ExtensionPackageRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `schema v2 artifact binds source lock tool metadata host access and http policy`() {
        val fixture = v2Fixture()

        val manifest = parser.parse(fixture.manifestContent, fixture.files)

        assertEquals(2, manifest.snapshot.schemaVersion)
        assertEquals("pi-register-tool-v1", manifest.snapshot.runtime)
        assertEquals("Fixture echo", manifest.snapshot.tools.single().label)
        assertEquals("device_calendar", manifest.snapshot.hostTools.single().targetTool)
        assertEquals(listOf("GET"), manifest.snapshot.httpPolicy.methods)
        assertEquals(listOf("https://status.example.test"), manifest.snapshot.networkOrigins)
    }

    @Test
    fun `Android parser accepts every deterministic public packer artifact`() {
        val loader = requireNotNull(javaClass.classLoader)
        val resource = requireNotNull(loader.getResource("mobile-extension-artifacts"))
        val root = File(resource.toURI())
        val expected = listOf(
            "async-progress-abort",
            "host-call",
            "network",
            "official-register-tool",
            "state",
        )

        val parsed = expected.map { id ->
            val directory = File(root, id)
            val files = directory.walkTopDown().filter(File::isFile).map { file ->
                val bytes = file.readBytes()
                ExtensionPackageFile(
                    relativePath = file.relativeTo(directory).invariantSeparatorsPath,
                    mimeType = if (file.extension == "json") "application/json" else "text/javascript",
                    content = bytes,
                    contentSha256 = bytes.sha256(),
                )
            }.toList().sortedBy(ExtensionPackageFile::relativePath)
            val manifest = files.single { it.relativePath == EXTENSION_MANIFEST_PATH }
            val rootJson = Json.parseToJsonElement(manifest.content.toString(Charsets.UTF_8))
                .jsonObject
            assertEquals(
                "$id packer and Android digest must agree",
                rootJson.getValue("packageDigest").jsonPrimitive.content,
                extensionPackageDigest(JsonObject(rootJson - "packageDigest"), files),
            )
            parser.parse(manifest.content.toString(Charsets.UTF_8), files)
        }

        assertEquals(expected.size, parsed.size)
        assertTrue(parsed.all { it.snapshot.schemaVersion == 2 })
        assertTrue(parsed.all { it.snapshot.runtime == "pi-register-tool-v1" })
    }

    @Test
    fun `schema v2 artifact rejects missing provenance undeclared host access and digest tamper`() {
        val fixture = v2Fixture()
        val withoutLock = fixture.files.filterNot { it.relativePath == EXTENSION_SOURCE_LOCK_PATH }
        assertEquals(
            "EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_MISSING",
            runCatching { parser.parse(fixture.manifestContent, withoutLock) }
                .exceptionOrNull()?.message,
        )

        val undeclared = v2Fixture(requiredCapabilities = emptyList(), parseManifest = false)
        assertEquals(
            "EXTENSION_PACKAGE_MOBILE_CAPABILITY_UNDECLARED",
            runCatching { parser.parse(undeclared.manifestContent, undeclared.files) }
                .exceptionOrNull()?.message,
        )

        val imageResultTarget = v2Fixture(
            requiredCapabilities = listOf("screen_capture"),
            optionalCapabilities = emptyList(),
            hostTargetTool = "device_screen_capture",
            hostCapability = "screen_capture",
            parseManifest = false,
        )
        assertEquals(
            "EXTENSION_PACKAGE_ALIAS_TARGET_UNSUPPORTED",
            runCatching {
                parser.parse(imageResultTarget.manifestContent, imageResultTarget.files)
            }.exceptionOrNull()?.message,
        )

        val tampered = fixture.files.map { file ->
            if (file.relativePath == "dist/index.js") extensionFile("dist/index.js", "tampered")
            else file
        }
        assertEquals(
            "EXTENSION_PACKAGE_DIGEST_MISMATCH",
            runCatching { parser.parse(fixture.manifestContent, tampered) }
                .exceptionOrNull()?.message,
        )
    }

    @Test
    fun `schema v2 install and Worker limits reject drift at the Android boundary`() {
        listOf(
            """{"type":"string"}""",
            """{"type":"object","properties":{"value":{"const":null}},"required":["value"],"additionalProperties":false}""",
            """{"type":"object","properties":{"value":{"type":"string","enum":[1]}},"required":["value"],"additionalProperties":false}""",
            """{"type":"object","properties":{"value":{"type":"number","enum":[0,-0]}},"required":["value"],"additionalProperties":false}""",
        ).forEach { raw ->
            assertEquals(
                "EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID",
                runCatching {
                    requireValidPiRegisterToolSchema(Json.parseToJsonElement(raw).jsonObject)
                }.exceptionOrNull()?.message,
            )
        }

        val longId = "a.${"b".repeat(75)}"
        assertEquals(
            "EXTENSION_PACKAGE_MANIFEST_FIELD_INVALID",
            runCatching {
                val fixture = v2Fixture(id = longId, parseManifest = false)
                parser.parse(fixture.manifestContent, fixture.files)
            }.exceptionOrNull()?.message,
        )
        assertEquals(
            "EXTENSION_PACKAGE_ARTIFACT_INVALID",
            runCatching {
                val fixture = v2Fixture(
                    extraFiles = listOf(
                        extensionFile("resources/large.txt", "r".repeat(64 * 1024 + 1)),
                    ),
                    parseManifest = false,
                )
                parser.parse(fixture.manifestContent, fixture.files)
            }.exceptionOrNull()?.message,
        )
        assertEquals(
            "EXTENSION_PACKAGE_ARTIFACT_INVALID",
            runCatching {
                val fixture = v2Fixture(
                    extraFiles = (0 until 32).map { index ->
                        extensionFile("dist/extra-$index.js", "export const value$index = $index;\n")
                    },
                    parseManifest = false,
                )
                parser.parse(fixture.manifestContent, fixture.files)
            }.exceptionOrNull()?.message,
        )
        assertEquals(
            "EXTENSION_PACKAGE_ARTIFACT_INVALID",
            runCatching {
                val fixture = v2Fixture(
                    extraFiles = listOf(extensionFile("dist/config.txt", "not a module")),
                    parseManifest = false,
                )
                parser.parse(fixture.manifestContent, fixture.files)
            }.exceptionOrNull()?.message,
        )
        listOf(
            "https://bücher.example/repo",
            "https://a_b.example/repo",
            "https://example.test/%zz",
        ).forEach { repositoryUrl ->
            assertEquals(
                "EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID",
                runCatching {
                    val fixture = v2Fixture(
                        sourceKind = "git",
                        repositoryUrl = repositoryUrl,
                        revision = "a".repeat(40),
                        parseManifest = false,
                    )
                    parser.parse(fixture.manifestContent, fixture.files)
                }.exceptionOrNull()?.message,
            )
        }
    }

    @Test
    fun `runtime migration installs disabled clears state reports access diff and survives restore`() = runTest {
        val old = v1Fixture()
        val oldManifest = requireNotNull(old.manifest)
        repository.install(oldManifest, old.files)
        repository.setEnabled(oldManifest.snapshot.id, true)
        repository.commitJavaScriptState(
            oldManifest.snapshot.id,
            oldManifest.snapshot.packageDigest,
            mapOf("count" to "1"),
        )
        val next = v2Fixture()

        val result = repository.installWithResult(
            parser.parse(next.manifestContent, next.files),
            next.files,
        )

        assertTrue(result.wasUpdate)
        assertFalse(result.unchanged)
        assertFalse(result.record.enabled)
        assertTrue(result.accessDiff.runtimeChanged)
        assertEquals(listOf("calendar"), result.accessDiff.addedRequiredCapabilities)
        assertEquals(listOf("contacts"), result.accessDiff.removedRequiredCapabilities)
        assertEquals(listOf("contacts"), result.accessDiff.addedOptionalCapabilities)
        assertEquals(listOf("calendar"), result.accessDiff.removedOptionalCapabilities)
        assertEquals(
            listOf("calendar -> device_calendar [calendar]"),
            result.accessDiff.addedHostTools,
        )
        assertEquals(listOf("https://status.example.test"), result.accessDiff.addedOrigins)
        assertEquals(listOf("https://old.example.test"), result.accessDiff.removedOrigins)
        assertEquals(listOf("GET"), result.accessDiff.addedHttpMethods)
        assertEquals(
            listOf("status-api @ https://status.example.test (authorization_bearer)"),
            result.accessDiff.addedCredentialBindings,
        )
        assertTrue(database.extensionPackageStateDao().entries(next.id).isEmpty())
        assertEquals("pi-register-tool-v1", repository.packageRecords().single().manifest.snapshot.runtime)

        repository.setEnabled(next.id, true)
        val invocation = repository.piRegisterToolInvocation(
            next.id,
            result.record.manifest.snapshot.packageDigest,
            "fixture_echo",
        )
        assertEquals("dist/index.js", invocation.artifact.entrypoint)
        assertTrue(invocation.artifact.modules.containsKey("dist/index.js"))
        assertTrue(invocation.state.isEmpty())
        assertTrue(repository.commitPiRegisterToolState(PiExtensionStateCommit(
            packageId = next.id,
            packageDigest = result.record.manifest.snapshot.packageDigest,
            toolName = "fixture_echo",
            artifact = invocation.artifact,
            state = mapOf("count" to JsonPrimitive(1)),
        )))
        assertEquals(
            JsonPrimitive(1),
            repository.piRegisterToolInvocation(
                next.id,
                result.record.manifest.snapshot.packageDigest,
                "fixture_echo",
            ).state["count"],
        )
        repository.setEnabled(next.id, false)
        assertEquals(
            "EXTENSION_PACKAGE_NOT_ENABLED",
            runCatching {
                repository.piRegisterToolInvocation(
                    next.id,
                    result.record.manifest.snapshot.packageDigest,
                    "fixture_echo",
                )
            }.exceptionOrNull()?.message,
        )
        assertTrue(repository.enabledPackageSet().packages.isEmpty())
        assertTrue(repository.delete(next.id))
        assertTrue(repository.packageRecords().isEmpty())
    }

    private fun v2Fixture(
        id: String = "com.example.mobile-pi",
        requiredCapabilities: List<String> = listOf("calendar"),
        optionalCapabilities: List<String> = listOf("contacts"),
        extraFiles: List<ExtensionPackageFile> = emptyList(),
        sourceKind: String = "local",
        repositoryUrl: String? = null,
        revision: String? = null,
        hostTargetTool: String = "device_calendar",
        hostCapability: String? = "calendar",
        parseManifest: Boolean = true,
    ): Fixture {
        val module = extensionFile(
            "dist/index.js",
            "export default function(pi){pi.registerTool({name:'fixture_echo'});}\n",
            "text/javascript",
        )
        val sourceLock = buildJsonObject {
            put("schemaVersion", 1)
            put("profile", "momoding-pi-mobile-v1")
            put("kind", sourceKind)
            put("packageName", "@example/mobile-pi")
            put("packageVersion", "2.0.0")
            put("sourceDigest", "1".repeat(64))
            put("registryIntegrity", JsonNull)
            put("repositoryUrl", repositoryUrl?.let(::JsonPrimitive) ?: JsonNull)
            put("revision", revision?.let(::JsonPrimitive) ?: JsonNull)
            put("packer", buildJsonObject {
                put("node", "22.22.3")
                put("typescript", "5.9.3")
                put("esbuild", "0.27.2")
            })
        }
        val lock = extensionFile(
            EXTENSION_SOURCE_LOCK_PATH,
            canonicalJson(sourceLock),
            "application/json",
        )
        val payload = listOf(module, lock) + extraFiles
        val base = buildJsonObject {
            put("schemaVersion", 2)
            put("id", id)
            put("name", "Mobile Pi fixture")
            put("version", "2.0.0")
            put("description", "A deterministic schema v2 fixture.")
            put("runtime", "pi-register-tool-v1")
            put("entrypoint", "dist/index.js")
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "pi-register-tool")
                    put("name", "fixture_echo")
                    put("label", "Fixture echo")
                    put("description", "Return one bounded value.")
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject { })
                        put("additionalProperties", false)
                    })
                    put("promptSnippet", JsonNull)
                    put("promptGuidelines", buildJsonArray { })
                    put("executionMode", "sequential")
                })
            })
            put("hostTools", buildJsonArray {
                add(buildJsonObject {
                    put("name", "calendar")
                    put("targetTool", hostTargetTool)
                    put("capability", hostCapability?.let(::JsonPrimitive) ?: JsonNull)
                })
            })
            put("requiredCapabilities", buildJsonArray {
                requiredCapabilities.forEach { add(JsonPrimitive(it)) }
            })
            put("optionalCapabilities", buildJsonArray {
                optionalCapabilities.forEach { add(JsonPrimitive(it)) }
            })
            put("httpPolicy", buildJsonObject {
                put("origins", buildJsonArray { add(JsonPrimitive("https://status.example.test")) })
                put("methods", buildJsonArray { add(JsonPrimitive("GET")) })
                put("credentialSlots", buildJsonArray {
                    add(buildJsonObject {
                        put("slot", "status-api")
                        put("origin", "https://status.example.test")
                        put("placement", "authorization_bearer")
                    })
                })
            })
        }
        val digest = extensionPackageDigest(base, payload)
        val full = JsonObject(base + ("packageDigest" to JsonPrimitive(digest)))
        val raw = canonicalJson(full)
        val files = (payload + extensionFile(EXTENSION_MANIFEST_PATH, raw, "application/json"))
            .sortedBy(ExtensionPackageFile::relativePath)
        return Fixture(id, raw, files, if (parseManifest) parser.parse(raw, files) else null)
    }

    private fun v1Fixture(): Fixture {
        val id = "com.example.mobile-pi"
        val readme = extensionFile("README.md", "old")
        val base = buildJsonObject {
            put("schemaVersion", 1)
            put("id", id)
            put("name", "Old fixture")
            put("version", "1.0.0")
            put("description", "Old runtime.")
            put("runtime", "declarative-v1")
            put("entrypoint", JsonNull)
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "prompt-tool")
                    put("name", "old_tool")
                    put("description", "Old Tool.")
                    put("prompt", "Old")
                })
            })
            put("requiredCapabilities", buildJsonArray { add(JsonPrimitive("contacts")) })
            put("optionalCapabilities", buildJsonArray { add(JsonPrimitive("calendar")) })
            put("networkOrigins", buildJsonArray {
                add(JsonPrimitive("https://old.example.test"))
            })
        }
        val digest = extensionPackageDigest(base, listOf(readme))
        val full = JsonObject(base + ("packageDigest" to JsonPrimitive(digest)))
        val raw = canonicalJson(full)
        val files = listOf(readme, extensionFile(EXTENSION_MANIFEST_PATH, raw, "application/json"))
        return Fixture(id, raw, files, parser.parse(raw, files))
    }

    private fun extensionFile(
        path: String,
        content: String,
        mime: String = "text/plain",
    ): ExtensionPackageFile {
        val bytes = content.toByteArray()
        return ExtensionPackageFile(path, mime, bytes, bytes.sha256())
    }

    private data class Fixture(
        val id: String,
        val manifestContent: String,
        val files: List<ExtensionPackageFile>,
        val manifest: ExtensionPackageManifest?,
    )
}
