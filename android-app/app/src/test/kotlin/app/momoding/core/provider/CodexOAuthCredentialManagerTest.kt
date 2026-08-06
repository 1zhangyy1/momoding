package app.momoding.core.provider

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CodexOAuthCredentialManagerTest {
    @Test
    fun `unexpired credential is returned without refresh`() = runBlocking {
        val fixture = fixture()
        val current = credential(expiresAtMillis = fixture.now + 120_000)
        fixture.manager.store(current)

        assertEquals(current, fixture.manager.requireValidCredential())
        assertEquals(0, fixture.gateway.refreshCount.get())
    }

    @Test
    fun `concurrent expired reads serialize one rotating refresh`() = runBlocking {
        val fixture = fixture()
        fixture.manager.store(credential(expiresAtMillis = fixture.now - 1))

        val credentials = List(12) {
            async { fixture.manager.requireValidCredential() }
        }.awaitAll()

        assertEquals(1, fixture.gateway.refreshCount.get())
        assertEquals(1, credentials.map(CodexOAuthCredential::refreshToken).distinct().size)
        assertEquals("rotated-refresh", credentials.first().refreshToken)
        assertEquals(credentials.first(), fixture.manager.current())
    }

    @Test
    fun `account mismatch fails closed and preserves previous credential`() {
        val fixture = fixture(refreshedAccountId = "different-account")
        val current = credential(expiresAtMillis = fixture.now - 1)
        fixture.manager.store(current)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { fixture.manager.requireValidCredential() }
        }

        assertEquals(current, fixture.manager.current())
    }

    @Test
    fun `unauthorized token refreshes once but stale rejection reuses rotation`() = runBlocking {
        val fixture = fixture()
        fixture.manager.store(credential(expiresAtMillis = fixture.now + 120_000))

        val refreshed = fixture.manager.refreshAfterUnauthorized("header.payload.signature")
        val reused = fixture.manager.refreshAfterUnauthorized("header.payload.signature")

        assertEquals("rotated.header.signature", refreshed.accessToken)
        assertEquals(refreshed, reused)
        assertEquals(1, fixture.gateway.refreshCount.get())
    }

    @Test
    fun `logout removes credential without provider network call`() {
        val fixture = fixture()
        fixture.manager.store(credential(expiresAtMillis = fixture.now + 120_000))

        fixture.manager.logout()

        assertEquals(null, fixture.manager.current())
        assertEquals(0, fixture.gateway.refreshCount.get())
        assertFalse(fixture.vault.exists())
    }

    private fun fixture(refreshedAccountId: String = "account-123"): ManagerFixture {
        val now = 1_800_000_000_000L
        val vault = CodexOAuthVault(TestCodexAead(), MemoryCodexVaultFileStore())
        val gateway = RefreshingGateway(now, refreshedAccountId)
        return ManagerFixture(
            now = now,
            vault = vault,
            gateway = gateway,
            manager = CodexOAuthCredentialManager(vault, gateway) { now },
        )
    }

    private fun credential(expiresAtMillis: Long) = CodexOAuthCredential(
        accessToken = "header.payload.signature",
        refreshToken = "initial-refresh",
        expiresAtMillis = expiresAtMillis,
        accountId = "account-123",
    )
}

private data class ManagerFixture(
    val now: Long,
    val vault: CodexOAuthVault,
    val gateway: RefreshingGateway,
    val manager: CodexOAuthCredentialManager,
)

private class RefreshingGateway(
    private val now: Long,
    private val refreshedAccountId: String,
) : CodexOAuthGateway {
    val refreshCount = AtomicInteger()

    override suspend fun startDeviceAuthorization(): CodexDeviceAuthorization = error("unused")

    override suspend fun pollDeviceAuthorization(
        authorization: CodexDeviceAuthorization,
    ): CodexDevicePollResult = error("unused")

    override suspend fun refresh(refreshToken: String): CodexOAuthCredential {
        refreshCount.incrementAndGet()
        delay(10)
        return CodexOAuthCredential(
            accessToken = "rotated.header.signature",
            refreshToken = "rotated-refresh",
            expiresAtMillis = now + 3_600_000,
            accountId = refreshedAccountId,
        )
    }
}
