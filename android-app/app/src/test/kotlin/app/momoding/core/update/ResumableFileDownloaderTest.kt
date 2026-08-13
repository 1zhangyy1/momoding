package app.momoding.core.update

import java.nio.file.Files
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumableFileDownloaderTest {
    @Test
    fun `continues an existing partial file with a range request`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 4-9/10")
                    .setBody("456789"),
            )
            val partial = temporaryPartial("0123")
            val progress = mutableListOf<Int?>()

            downloader().download(asset(server, 10), partial, progress::add)

            assertEquals("0123456789", partial.readText())
            assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
            assertEquals(40, progress.first())
            assertEquals(100, progress.last())
        }
    }

    @Test
    fun `restarts safely when the server ignores the range request`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("abcdefghij"))
            val partial = temporaryPartial("old-")

            downloader().download(asset(server, 10), partial) {}

            assertEquals("abcdefghij", partial.readText())
            assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
        }
    }

    @Test
    fun `keeps received bytes and resumes after a response is interrupted`() {
        MockWebServer().use { server ->
            val completeBody = buildString(64 * 1024) {
                repeat(64 * 1024) { index -> append(('a'.code + index % 26).toChar()) }
            }
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(completeBody)
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )
            val partial = temporaryPartial()

            val firstAttempt = runCatching {
                downloader().download(asset(server, completeBody.length.toLong()), partial) {}
            }

            assertTrue(firstAttempt.isFailure)
            assertTrue(partial.isFile)
            val retainedBytes = partial.length()
            assertTrue(retainedBytes in 1 until completeBody.length.toLong())
            assertEquals(null, server.takeRequest().getHeader("Range"))

            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader(
                        "Content-Range",
                        "bytes $retainedBytes-${completeBody.lastIndex}/${completeBody.length}",
                    )
                    .setBody(completeBody.substring(retainedBytes.toInt())),
            )

            downloader().download(asset(server, completeBody.length.toLong()), partial) {}

            assertEquals("bytes=$retainedBytes-", server.takeRequest().getHeader("Range"))
            assertEquals(completeBody, partial.readText())
        }
    }

    @Test
    fun `restarts once after the server rejects a stale range`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(416))
            server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))
            val partial = temporaryPartial("stale")

            downloader().download(asset(server, 10), partial) {}

            assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
            assertEquals(null, server.takeRequest().getHeader("Range"))
            assertEquals("0123456789", partial.readText())
        }
    }

    @Test
    fun `rejects a mismatched content range without changing the partial`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 5-9/10")
                    .setBody("56789"),
            )
            val partial = temporaryPartial("0123")

            assertThrows(IllegalStateException::class.java) {
                downloader().download(asset(server, 10), partial) {}
            }

            assertEquals("0123", partial.readText())
        }
    }

    @Test
    fun `rejects an oversized response before overwriting saved progress`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("01234567890"))
            val partial = temporaryPartial("keep")

            assertThrows(IllegalStateException::class.java) {
                downloader().download(asset(server, 10), partial) {}
            }

            assertEquals("keep", partial.readText())
        }
    }

    private fun downloader() = ResumableFileDownloader(OkHttpClient())

    private fun asset(server: MockWebServer, size: Long) = AppReleaseAsset(
        name = "momoding.apk",
        downloadUrl = server.url("/momoding.apk").toString(),
        sizeBytes = size,
    )

    private fun temporaryPartial(contents: String = "") =
        Files.createTempDirectory("momoding-update-")
            .resolve("momoding.apk.part")
            .toFile()
            .apply { writeText(contents) }
}
