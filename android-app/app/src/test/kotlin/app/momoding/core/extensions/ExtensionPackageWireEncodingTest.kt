package app.momoding.core.extensions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionPackageWireEncodingTest {
    @Test
    fun `wire encoding keeps strict null and omits irrelevant tool fields`() {
        val encoded = extensionPackageSnapshotsWireJson(
            listOf(
                ExtensionPackageSnapshot(
                    schemaVersion = 1,
                    id = "com.momoding.fixture",
                    name = "Fixture",
                    version = "1.0.0",
                    description = "Strict wire fixture.",
                    runtime = "declarative-v1",
                    tools = listOf(
                        ExtensionToolSnapshot(
                            type = "android-tool-alias",
                            name = "device_status",
                            description = "Read status.",
                            targetTool = "device_capabilities_get",
                        ),
                        ExtensionToolSnapshot(
                            type = "prompt-tool",
                            name = "checklist",
                            description = "Return checklist.",
                            prompt = "Ready",
                        ),
                    ),
                    requiredCapabilities = emptyList(),
                    optionalCapabilities = emptyList(),
                    networkOrigins = emptyList(),
                    packageDigest = "a".repeat(64),
                ),
            ),
        )

        val root = Json.parseToJsonElement(encoded).jsonArray.single().jsonObject
        assertEquals(
            setOf(
                "schemaVersion", "id", "name", "version", "description", "runtime", "entrypoint",
                "tools", "requiredCapabilities", "optionalCapabilities", "networkOrigins", "packageDigest",
            ),
            root.keys,
        )
        assertTrue(root.getValue("entrypoint").toString() == "null")
        val tools = root.getValue("tools").jsonArray
        assertEquals(setOf("type", "name", "description", "targetTool"), tools[0].jsonObject.keys)
        assertEquals("device_capabilities_get", tools[0].jsonObject.getValue("targetTool").jsonPrimitive.content)
        assertEquals(setOf("type", "name", "description", "prompt"), tools[1].jsonObject.keys)
        assertEquals("Ready", tools[1].jsonObject.getValue("prompt").jsonPrimitive.content)
    }

    @Test
    fun `wire encoding preserves javascript entrypoint and binds numeric schema by digest`() {
        val parameters = Json.parseToJsonElement(
            """{"type":"object","properties":{"amount":{"type":"number","minimum":1.0,"maximum":1e2,"enum":[1.0,2.5]}},"required":["amount"],"additionalProperties":false}""",
        ).jsonObject
        val encoded = extensionPackageSnapshotsWireJson(
            listOf(
                ExtensionPackageSnapshot(
                    schemaVersion = 1,
                    id = "com.momoding.javascript-fixture",
                    name = "JavaScript fixture",
                    version = "1.0.0",
                    description = "Strict JavaScript wire fixture.",
                    runtime = "javascript-v1",
                    entrypoint = "dist/index.js",
                    tools = listOf(
                        ExtensionToolSnapshot(
                            type = "javascript-tool",
                            name = "fixture_status",
                            description = "Run isolated JavaScript.",
                            parameters = parameters,
                        ),
                    ),
                    requiredCapabilities = emptyList(),
                    optionalCapabilities = emptyList(),
                    networkOrigins = emptyList(),
                    packageDigest = "b".repeat(64),
                ),
            ),
        )

        val root = Json.parseToJsonElement(encoded).jsonArray.single().jsonObject
        assertEquals("javascript-v1", root.getValue("runtime").jsonPrimitive.content)
        assertEquals("dist/index.js", root.getValue("entrypoint").jsonPrimitive.content)
        val tool = root.getValue("tools").jsonArray.single().jsonObject
        assertEquals(setOf("type", "name", "description", "parameters", "parametersDigest"), tool.keys)
        assertEquals(parameters, tool.getValue("parameters"))
        assertEquals(
            extensionToolParametersDigest(parameters),
            tool.getValue("parametersDigest").jsonPrimitive.content,
        )
    }
}
