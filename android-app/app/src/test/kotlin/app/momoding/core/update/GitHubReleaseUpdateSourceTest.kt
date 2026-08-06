package app.momoding.core.update

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubReleaseUpdateSourceTest {
    @Test
    fun `selects highest complete non-draft release`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    [
                      ${releaseJson(server, "v0.1.0-alpha.2")},
                      ${releaseJson(server, "v0.1.0-alpha.11", includeChecksum = false)},
                      ${releaseJson(server, "v0.1.0-alpha.10")},
                      ${releaseJson(server, "v9.0.0", draft = true)}
                    ]
                    """.trimIndent(),
                ),
            )

            val release = GitHubReleaseUpdateSource(
                releasesUrl = server.url("/releases").toString(),
            ).latestRelease()

            assertEquals("0.1.0-alpha.10", release?.versionName)
            assertEquals("momoding-0.1.0-alpha.10.apk", release?.apk?.name)
        }
    }

    @Test
    fun `ignores a release without both signed artifacts`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "[${releaseJson(server, "v0.1.0-alpha.2", includeChecksum = false)}]",
                ),
            )

            assertNull(
                GitHubReleaseUpdateSource(
                    releasesUrl = server.url("/releases").toString(),
                ).latestRelease(),
            )
        }
    }

    private fun releaseJson(
        server: MockWebServer,
        tag: String,
        includeChecksum: Boolean = true,
        draft: Boolean = false,
    ): String {
        val version = tag.removePrefix("v")
        val apkName = "momoding-$version.apk"
        val assets = buildList {
            add(
                """{"name":"$apkName","browser_download_url":"${server.url("/$apkName")}","size":60963069}""",
            )
            if (includeChecksum) {
                add(
                    """{"name":"$apkName.sha256","browser_download_url":"${server.url("/$apkName.sha256")}","size":93}""",
                )
            }
        }
        return """
            {
              "tag_name":"$tag",
              "name":"Momoding $version",
              "body":"Release notes",
              "draft":$draft,
              "published_at":"2026-08-06T08:55:06Z",
              "assets":[${assets.joinToString(",")}]
            }
        """.trimIndent()
    }
}
