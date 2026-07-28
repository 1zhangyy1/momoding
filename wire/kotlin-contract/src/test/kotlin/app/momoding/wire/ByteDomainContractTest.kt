package app.momoding.wire

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ByteDomainContractTest {
    private val fixture: JsonObject by lazy {
        val text = checkNotNull(javaClass.getResource("/pi-0.80.6/p1b-byte-domains.json")) {
            "P1B byte-domain fixture is missing from the test resources"
        }.readText()
        Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `uses the exact transmitted logical event bytes`() {
        val record = fixture.getValue("logicalEvent").jsonObject
        val bytes = Base64.getDecoder().decode(record.string("jsonBase64"))

        assertEquals(record.int("byteLength"), bytes.size)
        assertEquals(record.string("sha256"), sha256(bytes))
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("你好 Momoding"))
    }

    @Test
    fun `hashes raw snapshot pages with unsigned big-endian lengths`() {
        val digest = MessageDigest.getInstance("SHA-256")
        var historyCountedBytes = 0
        fixture.getValue("snapshotPages").jsonArray
            .map { it.jsonObject }
            .sortedBy { it.int("pageIndex") }
            .forEach { page ->
                val bytes = Base64.getDecoder().decode(page.string("jsonBase64"))
                assertEquals(page.int("byteLength"), bytes.size)
                assertEquals(page.string("sha256"), sha256(bytes))
                digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
                digest.update(bytes)
                historyCountedBytes += bytes.size
            }

        val expected = fixture.getValue("snapshotPageDigest").jsonObject
        assertEquals(
            "concat(uint64be(pageByteLength),pageLogicalBytes) ordered by pageIndex",
            expected.string("construction"),
        )
        assertEquals(expected.string("sha256"), digest.digest().toHex())
        assertEquals(fixture.int("historyCountedBytes"), historyCountedBytes)
    }

    @Test
    fun `reproduces structured logical and complete physical frame boundaries`() {
        assertEquals(
            boundary(P1bProtocol.SNAPSHOT_PAGE_MAX_PHYSICAL_BYTES),
            fixture.getValue("physicalFrameRecipes").jsonArray
                .map { it.jsonObject.int("targetBytes") },
        )
        assertEquals(
            boundary(P1bProtocol.DIRECT_PI_EVENT_MAX_BYTES),
            structuredTargets("directEvent"),
        )
        assertEquals(boundary(P1bProtocol.HISTORY_MAX_BYTES), structuredTargets("historyPage"))
        assertEquals(
            boundary(P1bProtocol.SNAPSHOT_MAX_LOGICAL_BYTES),
            structuredTargets("taskSnapshot"),
        )

        fixture.getValue("structuredBoundaryRecipes").jsonArray
            .map { it.jsonObject }
            .forEach { recipe ->
                val limit = when (recipe.string("domain")) {
                    "directEvent" -> P1bProtocol.DIRECT_PI_EVENT_MAX_BYTES
                    "historyPage" -> P1bProtocol.HISTORY_MAX_BYTES
                    else -> P1bProtocol.SNAPSHOT_MAX_LOGICAL_BYTES
                }
                assertEquals(
                    !recipe.string("name").endsWith("PlusOne"),
                    recipe.int("targetBytes") <= limit,
                )
                val prefixBytes = recipe.string("jsonPrefix").toByteArray(StandardCharsets.UTF_8)
                val suffixBytes = recipe.string("jsonSuffix").toByteArray(StandardCharsets.UTF_8)
                assertEquals(
                    recipe.int("targetBytes"),
                    prefixBytes.size + recipe.int("payloadBytes") + suffixBytes.size,
                )
                assertEquals(recipe.string("expectedSha256"), hashStructuredRecipe(recipe))
                val emptyTemplate = Json.parseToJsonElement(
                    recipe.string("jsonPrefix") + recipe.string("jsonSuffix"),
                ).jsonObject
                val expectedKind = when (recipe.string("domain")) {
                    "directEvent" -> "pi.event"
                    "historyPage" -> "task.snapshot.page"
                    else -> "task.snapshot"
                }
                assertEquals(expectedKind, emptyTemplate.string("kind"))
            }
        fixture.getValue("physicalFrameRecipes").jsonArray
            .map { it.jsonObject }
            .forEach { recipe ->
                val bytes = physicalFrameBytes(recipe)
                assertEquals(recipe.int("targetBytes"), bytes.size)
                assertEquals(recipe.string("expectedSha256"), sha256(bytes))
                assertEquals(
                    "task.snapshot.page",
                    Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8))
                        .jsonObject.string("kind"),
                )
            }
        val chunkFrames = fixture.getValue("chunkDataFrameRecipes").jsonArray
            .map { it.jsonObject }
            .map { recipe -> recipe to chunkDataFrameBytes(recipe) }
        assertEquals(listOf("maxAtLimit", "firstOverLimit"), chunkFrames.map { it.first.string("name") })
        chunkFrames.forEach { (recipe, bytes) ->
            assertEquals(recipe.int("physicalByteLength"), bytes.size)
            assertEquals(recipe.string("expectedSha256"), sha256(bytes))
            val parsed = Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
            assertEquals("transport.chunk.data", parsed.string("kind"))
            assertEquals(
                recipe.int("decodedByteLength"),
                Base64.getDecoder().decode(parsed.string("data")).size,
            )
        }
        assertEquals(P1bProtocol.SNAPSHOT_PAGE_MAX_PHYSICAL_BYTES, chunkFrames[0].second.size)
        assertTrue(chunkFrames[1].second.size > P1bProtocol.SNAPSHOT_PAGE_MAX_PHYSICAL_BYTES)

        val large = fixture.getValue("largeSnapshotCase").jsonObject
        val pageRecipe = fixture.getValue("physicalFrameRecipes").jsonArray
            .map { it.jsonObject }
            .single { it.string("name") == large.string("sourcePageRecipe") }
        val pageBytes = physicalFrameBytes(pageRecipe)
        val payload = patternPayload(
            large.int("textPayloadBytes"),
            pageRecipe.string("utf8Pattern"),
            pageRecipe.string("asciiRemainderByte"),
        )
        val taskSnapshotBytes =
            large.string("taskSnapshotJsonPrefix").toByteArray(StandardCharsets.UTF_8) +
                payload +
                large.string("taskSnapshotJsonSuffix").toByteArray(StandardCharsets.UTF_8)
        assertEquals(large.int("taskSnapshotByteLength"), taskSnapshotBytes.size)
        assertEquals(large.string("taskSnapshotSha256"), sha256(taskSnapshotBytes))
        assertTrue(taskSnapshotBytes.size > P1bProtocol.SNAPSHOT_PAGE_MAX_PHYSICAL_BYTES)
        assertEquals(
            "task.snapshot",
            Json.parseToJsonElement(taskSnapshotBytes.toString(StandardCharsets.UTF_8))
                .jsonObject.string("kind"),
        )
        val pageDigest = MessageDigest.getInstance("SHA-256")
        pageDigest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(pageBytes.size.toLong()).array())
        pageDigest.update(pageBytes)
        assertEquals(large.string("snapshotPageDigest"), pageDigest.digest().toHex())
    }

    private fun structuredTargets(domain: String): List<Int> =
        fixture.getValue("structuredBoundaryRecipes").jsonArray
            .map { it.jsonObject }
            .filter { it.string("domain") == domain }
            .map { it.int("targetBytes") }

    private fun boundary(limit: Int): List<Int> = listOf(limit - 1, limit, limit + 1)

    private fun hashStructuredRecipe(recipe: JsonObject): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(recipe.string("jsonPrefix").toByteArray(StandardCharsets.UTF_8))
        updatePattern(digest, recipe.int("payloadBytes"), recipe)
        digest.update(recipe.string("jsonSuffix").toByteArray(StandardCharsets.UTF_8))
        return digest.digest().toHex()
    }

    private fun updatePattern(
        digest: MessageDigest,
        targetBytes: Int,
        recipe: JsonObject,
    ) {
        val pattern = recipe.string("utf8Pattern")
        val remainderByte = recipe.string("asciiRemainderByte")
        val patternBytes = pattern.toByteArray(StandardCharsets.UTF_8)
        val remainderBytes = remainderByte.toByteArray(StandardCharsets.US_ASCII)
        assertEquals(3, patternBytes.size)
        assertEquals(1, remainderBytes.size)

        val copies = targetBytes / patternBytes.size
        val chunkCopies = 16_384
        val chunk = pattern.repeat(chunkCopies).toByteArray(StandardCharsets.UTF_8)
        repeat(copies / chunkCopies) { digest.update(chunk) }
        val remainingCopies = copies % chunkCopies
        if (remainingCopies > 0) {
            digest.update(pattern.repeat(remainingCopies).toByteArray(StandardCharsets.UTF_8))
        }
        repeat(targetBytes % patternBytes.size) { digest.update(remainderBytes) }
    }

    private fun physicalFrameBytes(recipe: JsonObject): ByteArray {
        val prefix = recipe.string("jsonPrefix").toByteArray(StandardCharsets.UTF_8)
        val suffix = recipe.string("jsonSuffix").toByteArray(StandardCharsets.UTF_8)
        val payloadBytes = recipe.int("targetBytes") - prefix.size - suffix.size
        assertTrue(payloadBytes >= 0)
        val payload = patternPayload(
            payloadBytes,
            recipe.string("utf8Pattern"),
            recipe.string("asciiRemainderByte"),
        )
        return prefix + payload + suffix
    }

    private fun chunkDataFrameBytes(recipe: JsonObject): ByteArray {
        val decodedByteLength = recipe.int("decodedByteLength")
        val decoded = patternPayload(
            decodedByteLength,
            recipe.string("utf8Pattern"),
            recipe.string("asciiRemainderByte"),
        )
        return (
            recipe.string("jsonPrefix") +
                Base64.getEncoder().encodeToString(decoded) +
                recipe.string("jsonSuffix")
        ).toByteArray(StandardCharsets.UTF_8)
    }

    private fun patternPayload(
        targetBytes: Int,
        pattern: String,
        remainderByte: String,
    ): ByteArray {
        val patternSize = pattern.toByteArray(StandardCharsets.UTF_8).size
        assertEquals(3, patternSize)
        val payload = (
            pattern.repeat(targetBytes / patternSize) +
                remainderByte.repeat(targetBytes % patternSize)
        ).toByteArray(StandardCharsets.UTF_8)
        assertEquals(targetBytes, payload.size)
        return payload
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
