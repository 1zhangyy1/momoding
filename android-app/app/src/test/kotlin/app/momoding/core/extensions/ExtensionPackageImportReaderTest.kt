package app.momoding.core.extensions

import android.net.Uri
import android.provider.DocumentsContract.Document
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtensionPackageImportReaderTest {
    private val treeUri = Uri.parse("content://extension-fixture/tree/root")

    @Test
    fun `copies one bounded tree without retaining provider identity`() {
        val manifest = "{}".toByteArray()
        val readme = "fixture".toByteArray()
        val reader = ExtensionPackageImportReader(
            FakeTreeAccess(
                children = mapOf(
                    "root" to listOf(
                        node("manifest", EXTENSION_MANIFEST_PATH, "application/json", manifest),
                        node("assets", "assets", Document.MIME_TYPE_DIR),
                    ),
                    "assets" to listOf(node("readme", "README.md", "", readme)),
                ),
                contents = mapOf("manifest" to manifest, "readme" to readme),
            ),
        )

        val document = reader.read(treeUri)

        assertEquals("fixture-extension", document.rootDisplayName)
        assertEquals("{}", document.manifestContent)
        assertEquals(
            listOf("assets/README.md", EXTENSION_MANIFEST_PATH),
            document.files.map(ExtensionPackageFile::relativePath),
        )
        assertEquals(
            "application/octet-stream",
            document.files.single { it.relativePath == "assets/README.md" }.mimeType,
        )
    }

    @Test
    fun `rejects provider cycles before opening any bytes`() {
        val access = FakeTreeAccess(
            children = mapOf(
                "root" to listOf(node("root", "loop", Document.MIME_TYPE_DIR)),
            ),
        )

        assertEquals(
            "EXTENSION_PACKAGE_CYCLE_DETECTED",
            runCatching { ExtensionPackageImportReader(access).read(treeUri) }
                .exceptionOrNull()?.message,
        )
        assertEquals(0, access.openCount)
    }

    @Test
    fun `diagnoses an unverified raw Pi candidate as build required without executing its entrypoint`() {
        val packageJson =
            """{"name":"community-pi-extension","version":"1.0.0","pi":{"extensions":["./index.ts"]}}"""
                .toByteArray()
        val mobileProfile = rawMobileProfile("./index.ts")
        val entrypoint =
            """export default function(pi) { pi.registerTool({}); throw new Error('must never execute during import'); }""".toByteArray()
        val access = FakeTreeAccess(
            children = mapOf(
                "root" to listOf(
                    node("package", "package.json", "application/json", packageJson),
                    node("profile", "momoding-mobile.json", "application/json", mobileProfile),
                    node("entrypoint", "index.ts", "text/plain", entrypoint),
                ),
            ),
            contents = mapOf(
                "package" to packageJson,
                "profile" to mobileProfile,
                "entrypoint" to entrypoint,
            ),
        )

        assertEquals(
            "EXTENSION_PACKAGE_MOBILE_BUILD_REQUIRED",
            runCatching { ExtensionPackageImportReader(access).read(treeUri) }
                .exceptionOrNull()?.message,
        )
        val diagnostic = (ExtensionPackageImportReader(access).readForImport(treeUri) as
            ExtensionPackageReadResult.Diagnostic).diagnostic
        assertEquals(ExtensionPackageCompatibility.BUILD_REQUIRED, diagnostic.compatibility)
        assertEquals(true, diagnostic.detail.contains("unverified"))
        assertEquals(6, access.openCount)
    }

    @Test
    fun `raw diagnosis rejects malformed and comment-only profile claims`() {
        val packageJson =
            """{"name":"candidate","version":"1.0.0","pi":{"extensions":["./index.ts"]}}"""
                .toByteArray()
        val malformed = """{"profileVersion":1,"source":{"entrypoint":"./index.ts"},"tools":[{}]}"""
            .toByteArray()
        val commentOnly = """// export default (pi) => pi.registerTool({})""".toByteArray()

        assertEquals(
            "EXTENSION_PACKAGE_MOBILE_PROFILE_INVALID",
            diagnosticFor(packageJson, malformed, commentOnly, "index.ts").code,
        )
        assertEquals(
            "EXTENSION_PACKAGE_MOBILE_SOURCE_INVALID",
            diagnosticFor(packageJson, rawMobileProfile("./index.ts"), commentOnly, "index.ts").code,
        )
    }

    @Test
    fun `raw diagnosis rejects dynamic dependencies and recognizes mjs and tsx candidates`() {
        val dynamicPackage =
            """{"name":"dynamic","version":"1.0.0","pi":{"extensions":["./index.ts"]}}"""
                .toByteArray()
        val dynamicSource =
            """export default async (pi) => { await import("runtime-only"); pi.registerTool({}); }"""
                .toByteArray()
        assertEquals(
            ExtensionPackageCompatibility.UNSUPPORTED,
            diagnosticFor(
                dynamicPackage,
                rawMobileProfile("./index.ts"),
                dynamicSource,
                "index.ts",
            ).compatibility,
        )

        listOf("index.mjs", "index.tsx").forEach { entrypoint ->
            val packageJson =
                """{"name":"candidate","version":"1.0.0","pi":{"extensions":["./$entrypoint"]}}"""
                    .toByteArray()
            val source = """export default (pi) => { pi.registerTool({}); }""".toByteArray()
            assertEquals(
                ExtensionPackageCompatibility.BUILD_REQUIRED,
                diagnosticFor(
                    packageJson,
                    rawMobileProfile("./$entrypoint"),
                    source,
                    entrypoint,
                ).compatibility,
            )
        }

        val harmlessLiteral = """
            const message = "business process. registerProvider( WebSocket";
            const docs = `import { readFile } from 'node:fs/promises';`;
            // require( and ctx.ui. are documentation, not executable APIs.
            // import fs from "node:fs";
            export default (pi) => { pi.registerTool({}); }
        """.trimIndent().toByteArray()
        assertEquals(
            ExtensionPackageCompatibility.BUILD_REQUIRED,
            diagnosticFor(
                dynamicPackage,
                rawMobileProfile("./index.ts"),
                harmlessLiteral,
                "index.ts",
            ).compatibility,
        )
    }

    @Test
    fun `diagnoses executable desktop globals as unsupported`() {
        val packageJson =
            """{"name":"desktop-only","version":"1.0.0","pi":{"extensions":["./index.ts"]}}"""
                .toByteArray()
        val entrypoint =
            """export default function(pi) { process.cwd(); pi.registerTool({}); }""".toByteArray()
        val diagnostic = diagnosticFor(
            packageJson,
            rawMobileProfile("./index.ts"),
            entrypoint,
            "index.ts",
        )

        assertEquals(ExtensionPackageCompatibility.UNSUPPORTED, diagnostic.compatibility)
        assertEquals("EXTENSION_PACKAGE_MOBILE_NODE_API_UNSUPPORTED", diagnostic.code)
    }

    @Test
    fun `rejects a reported oversized file before reading it`() {
        val oversized = ExtensionPackageTreeNode(
            documentId = "large",
            displayName = EXTENSION_MANIFEST_PATH,
            mimeType = "application/json",
            sizeBytes = MAX_EXTENSION_PACKAGE_FILE_BYTES.toLong() + 1,
            flags = 0,
        )
        val access = FakeTreeAccess(children = mapOf("root" to listOf(oversized)))

        assertEquals(
            "EXTENSION_PACKAGE_FILE_TOO_LARGE",
            runCatching { ExtensionPackageImportReader(access).read(treeUri) }
                .exceptionOrNull()?.message,
        )
        assertEquals(0, access.openCount)
    }

    @Test
    fun `rejects excessive directory nodes before opening package bytes`() {
        val directories = (0 until MAX_EXTENSION_PACKAGE_NODES).map { index ->
            node("directory-$index", "directory-$index", Document.MIME_TYPE_DIR)
        }
        val access = FakeTreeAccess(children = mapOf("root" to directories))

        assertEquals(
            "EXTENSION_PACKAGE_NODE_LIMIT_EXCEEDED",
            runCatching { ExtensionPackageImportReader(access).read(treeUri) }
                .exceptionOrNull()?.message,
        )
        assertEquals(0, access.openCount)
    }

    @Test
    fun `reserves sibling nodes before descending into a subtree`() {
        val nested = (0 until MAX_EXTENSION_PACKAGE_NODES - 2).map { index ->
            node("nested-$index", "nested-$index", Document.MIME_TYPE_DIR)
        }
        val access = FakeTreeAccess(
            children = mapOf(
                "root" to listOf(
                    node("left", "left", Document.MIME_TYPE_DIR),
                    node("right", "right", Document.MIME_TYPE_DIR),
                ),
                "left" to nested,
            ),
        )

        assertEquals(
            "EXTENSION_PACKAGE_NODE_LIMIT_EXCEEDED",
            runCatching { ExtensionPackageImportReader(access).read(treeUri) }
                .exceptionOrNull()?.message,
        )
        assertEquals(0, access.openCount)
    }

    private fun node(
        id: String,
        name: String,
        mimeType: String,
        content: ByteArray? = null,
    ) = ExtensionPackageTreeNode(
        documentId = id,
        displayName = name,
        mimeType = mimeType,
        sizeBytes = content?.size?.toLong(),
        flags = 0,
    )

    private fun diagnosticFor(
        packageJson: ByteArray,
        mobileProfile: ByteArray,
        entrypoint: ByteArray,
        entrypointName: String,
    ): ExtensionPackageCompatibilityDiagnostic {
        val access = FakeTreeAccess(
            children = mapOf(
                "root" to listOf(
                    node("package", "package.json", "application/json", packageJson),
                    node("profile", "momoding-mobile.json", "application/json", mobileProfile),
                    node("entrypoint", entrypointName, "text/plain", entrypoint),
                ),
            ),
            contents = mapOf(
                "package" to packageJson,
                "profile" to mobileProfile,
                "entrypoint" to entrypoint,
            ),
        )
        return (ExtensionPackageImportReader(access).readForImport(treeUri) as
            ExtensionPackageReadResult.Diagnostic).diagnostic
    }

    private fun rawMobileProfile(entrypoint: String) = """
        {
          "profileVersion":1,
          "package":{"id":"com.example.candidate","name":"Candidate","version":"1.0.0","description":"Candidate source."},
          "source":{"entrypoint":"$entrypoint"},
          "tools":[{
            "name":"candidate_tool","label":"Candidate","description":"Candidate Tool.",
            "parameters":{"type":"object","properties":{},"additionalProperties":false},
            "promptSnippet":null,"promptGuidelines":[],"executionMode":"sequential"
          }],
          "hostTools":[],
          "capabilities":{"required":[],"optional":[]},
          "https":{"origins":[],"methods":[]},
          "credentials":[]
        }
    """.trimIndent().toByteArray()

    private class FakeTreeAccess(
        private val children: Map<String, List<ExtensionPackageTreeNode>>,
        private val contents: Map<String, ByteArray> = emptyMap(),
    ) : ExtensionPackageTreeAccess {
        var openCount: Int = 0
            private set

        override fun root(treeUri: Uri) = ExtensionPackageTreeNode(
            documentId = "root",
            displayName = "fixture-extension",
            mimeType = Document.MIME_TYPE_DIR,
            sizeBytes = null,
            flags = 0,
        )

        override fun children(
            treeUri: Uri,
            parentDocumentId: String,
            maximumChildren: Int,
        ): List<ExtensionPackageTreeNode> = children[parentDocumentId].orEmpty()

        override fun open(treeUri: Uri, documentId: String): InputStream {
            openCount += 1
            return ByteArrayInputStream(requireNotNull(contents[documentId]))
        }
    }
}
