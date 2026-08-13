package app.momoding.core.extensions

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.MomodingDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JavaScriptExtensionPackageTest {
    private lateinit var database: MomodingDatabase
    private lateinit var repository: ExtensionPackageRepository
    private val parser = ExtensionPackageManifestParser()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java).build()
        repository = ExtensionPackageRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `javascript manifest freezes entrypoint schema and module boundary`() {
        val fixture = fixture()

        assertEquals("javascript-v1", fixture.manifest.snapshot.runtime)
        assertEquals("dist/index.js", fixture.manifest.snapshot.entrypoint)
        assertEquals(
            setOf("label"),
            fixture.manifest.snapshot.tools.first().parameters
                ?.get("properties")
                ?.let { it as JsonObject }
                ?.keys,
        )

        val outside = fixture.rawManifest.replace("dist/index.js", "index.js")
        assertEquals(
            "EXTENSION_PACKAGE_ENTRYPOINT_INVALID",
            runCatching { parser.parse(outside, fixture.files.withManifest(outside)) }
                .exceptionOrNull()?.message,
        )
        val executableDeclarative = fixture.rawManifest
            .replace("\"javascript-v1\"", "\"declarative-v1\"")
            .replace("\"dist/index.js\"", "null")
        assertEquals(
            "EXTENSION_PACKAGE_TOOL_TYPE_UNSUPPORTED",
            runCatching { parser.parse(executableDeclarative, fixture.files.withManifest(executableDeclarative)) }
                .exceptionOrNull()?.message,
        )
    }

    @Test
    fun `numeric schema enums survive javascript canonicalization and reject unsafe integers`() {
        val numberSchema = Json.parseToJsonElement(
            """{"type":"object","properties":{"amount":{"type":"number","enum":[1.0,2.5]}},"required":["amount"],"additionalProperties":false}""",
        ) as JsonObject
        requireValidExtensionToolSchema(numberSchema)
        requireValidExtensionToolArguments(
            numberSchema,
            buildJsonObject { put("amount", 1) },
        )

        val unsafeIntegerSchema = Json.parseToJsonElement(
            """{"type":"object","properties":{"amount":{"type":"integer","enum":[9007199254740992]}},"required":["amount"],"additionalProperties":false}""",
        ) as JsonObject
        assertEquals(
            "EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID",
            runCatching { requireValidExtensionToolSchema(unsafeIntegerSchema) }
                .exceptionOrNull()?.message,
        )
    }

    @Test
    fun `javascript invocation validates live schema and persists bounded private state`() = runTest {
        val fixture = fixture()
        repository.install(fixture.manifest, fixture.files)
        repository.setEnabled(fixture.manifest.snapshot.id, true)
        val declaration = fixture.manifest.snapshot.tools.first()
        val arguments = buildJsonObject { put("label", "phone") }

        val first = repository.javascriptInvocation(
            packageId = fixture.manifest.snapshot.id,
            packageDigest = fixture.manifest.snapshot.packageDigest,
            toolName = declaration.name,
            description = declaration.description,
            parametersDigest = extensionToolParametersDigest(requireNotNull(declaration.parameters)),
            arguments = arguments,
        )
        assertEquals(emptyMap<String, String>(), first.stateJson)
        assertEquals(
            "EXTENSION_PACKAGE_TOOL_NOT_AUTHORIZED",
            runCatching {
                repository.javascriptInvocation(
                    fixture.manifest.snapshot.id,
                    fixture.manifest.snapshot.packageDigest,
                    declaration.name,
                    declaration.description,
                    "0".repeat(64),
                    arguments,
                )
            }.exceptionOrNull()?.message,
        )

        repository.commitJavaScriptState(
            fixture.manifest.snapshot.id,
            fixture.manifest.snapshot.packageDigest,
            mapOf("count" to "1", "profile" to "{\"label\":\"phone\"}"),
        )
        val restored = repository.javascriptInvocation(
            fixture.manifest.snapshot.id,
            fixture.manifest.snapshot.packageDigest,
            declaration.name,
            declaration.description,
            extensionToolParametersDigest(requireNotNull(declaration.parameters)),
            arguments,
        )
        assertEquals("1", restored.stateJson["count"])
        assertEquals(
            "EXTENSION_PACKAGE_TOOL_ARGUMENTS_INVALID",
            runCatching {
                repository.javascriptInvocation(
                    fixture.manifest.snapshot.id,
                    fixture.manifest.snapshot.packageDigest,
                    declaration.name,
                    declaration.description,
                    extensionToolParametersDigest(requireNotNull(declaration.parameters)),
                    buildJsonObject { put("unexpected", true) },
                )
            }.exceptionOrNull()?.message,
        )

        repository.setEnabled(fixture.manifest.snapshot.id, false)
        assertFalse(repository.packageRecords().single().enabled)
        assertEquals(
            "EXTENSION_PACKAGE_NOT_ENABLED",
            runCatching {
                repository.commitJavaScriptState(
                    fixture.manifest.snapshot.id,
                    fixture.manifest.snapshot.packageDigest,
                    mapOf("count" to "2"),
                )
            }.exceptionOrNull()?.message,
        )

        val update = fixture(version = "1.1.0")
        repository.install(update.manifest, update.files)
        repository.setEnabled(update.manifest.snapshot.id, true)
        val updatedDeclaration = update.manifest.snapshot.tools.first()
        val afterUpdate = repository.javascriptInvocation(
            update.manifest.snapshot.id,
            update.manifest.snapshot.packageDigest,
            updatedDeclaration.name,
            updatedDeclaration.description,
            extensionToolParametersDigest(requireNotNull(updatedDeclaration.parameters)),
            arguments,
        )
        assertEquals(emptyMap<String, String>(), afterUpdate.stateJson)
    }

    @Test
    fun `pxp6c validation fixture digest stays reproducible`() {
        val payload = listOf(
            file("README.md", "PXP-6C isolated JavaScript Extension fixture.\n"),
            file(
                "dist/index.js",
                """
                    import { callTool, getState, readResource, registerTool, setState } from '@momoding/sdk';

                    registerTool({
                      name: 'fixture_js_status',
                      execute: ({ mode }) => {
                        if (mode === 'device') return callTool('fixture_js_device_status', {});
                        const count = (getState('count') ?? 0) + 1;
                        setState('count', count);
                        return `${'$'}{readResource('references/status.txt').trim()}:${'$'}{count}`;
                      },
                    });
                """.trimIndent() + "\n",
                "text/javascript",
            ),
            file("references/status.txt", "PXP6C_JS_OK\n", "text/plain"),
        )
        val manifestWithoutDigest = buildJsonObject {
            put("schemaVersion", 1)
            put("id", "com.momoding.fixture.javascript-helper")
            put("name", "JavaScript helper fixture")
            put("version", "1.0.0")
            put("description", "PXP-6C isolated JavaScript emulator gate package.")
            put("runtime", "javascript-v1")
            put("entrypoint", "dist/index.js")
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "javascript-tool")
                    put("name", "fixture_js_status")
                    put("description", "Run isolated package JavaScript.")
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("mode", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add(JsonPrimitive("local"))
                                    add(JsonPrimitive("device"))
                                })
                            })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("mode")) })
                        put("additionalProperties", false)
                    })
                })
                add(buildJsonObject {
                    put("type", "android-tool-alias")
                    put("name", "fixture_js_device_status")
                    put(
                        "description",
                        "Read live Android capability state from isolated JavaScript.",
                    )
                    put("targetTool", "device_capabilities_get")
                })
            })
            put("requiredCapabilities", buildJsonArray { })
            put("optionalCapabilities", buildJsonArray { })
            put("networkOrigins", buildJsonArray { })
        }

        assertEquals(
            listOf(
                "README.md:46:0883ddbcf8267322536e4c660a0d0041ff844b5f42655a7e4381b2a9132fabbe",
                "dist/index.js:396:0cfd9a0681b53e5579f566621290911ccd3467d18839b4e175388197e7345382",
                "references/status.txt:12:65c7465e490db90444fe8eb515c2f9ecd73f1144fac5f0643a4e5068a664b20e",
            ),
            payload.map { "${it.relativePath}:${it.content.size}:${it.contentSha256}" },
        )
        assertEquals(
            "8b68872d98b525651e30bbfbcc6eb56bbc1b92830e012b0618ff1447c00bf892",
            extensionPackageDigest(manifestWithoutDigest, payload),
        )
    }

    private fun fixture(version: String = "1.0.0"): Fixture {
        val payload = listOf(
            file(
                "dist/index.js",
                "import { registerTool } from '@momoding/sdk';\n" +
                    "registerTool({ name: 'extension_js_status', execute: ({ label }) => label });\n",
                "text/javascript",
            ),
            file("references/help.md", "Only package resources are readable."),
        )
        val base = buildJsonObject {
            put("schemaVersion", 1)
            put("id", "com.example.javascript-helper")
            put("name", "JavaScript helper")
            put("version", version)
            put("description", "An isolated JavaScript fixture.")
            put("runtime", "javascript-v1")
            put("entrypoint", "dist/index.js")
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "javascript-tool")
                    put("name", "extension_js_status")
                    put("description", "Run isolated package JavaScript.")
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("label", buildJsonObject {
                                put("type", "string")
                                put("minLength", 1)
                                put("maxLength", 32)
                            })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("label")) })
                        put("additionalProperties", false)
                    })
                })
                add(buildJsonObject {
                    put("type", "android-tool-alias")
                    put("name", "extension_device_status")
                    put("description", "Use the existing device capability Tool.")
                    put("targetTool", "device_capabilities_get")
                })
            })
            put("requiredCapabilities", buildJsonArray { })
            put("optionalCapabilities", buildJsonArray { })
            put("networkOrigins", buildJsonArray { })
        }
        val digest = extensionPackageDigest(base, payload)
        val full = JsonObject(base + ("packageDigest" to JsonPrimitive(digest)))
        val raw = canonicalJson(full)
        val files = (payload + file(EXTENSION_MANIFEST_PATH, raw, "application/json"))
            .sortedBy(ExtensionPackageFile::relativePath)
        return Fixture(parser.parse(raw, files), files, raw)
    }

    private fun file(path: String, content: String, mimeType: String = "text/markdown"):
        ExtensionPackageFile {
        val bytes = content.toByteArray()
        return ExtensionPackageFile(path, mimeType, bytes, bytes.sha256())
    }

    private fun List<ExtensionPackageFile>.withManifest(raw: String): List<ExtensionPackageFile> =
        map { candidate ->
            if (candidate.relativePath == EXTENSION_MANIFEST_PATH) {
                file(EXTENSION_MANIFEST_PATH, raw, "application/json")
            } else candidate
        }

    private data class Fixture(
        val manifest: ExtensionPackageManifest,
        val files: List<ExtensionPackageFile>,
        val rawManifest: String,
    )
}
