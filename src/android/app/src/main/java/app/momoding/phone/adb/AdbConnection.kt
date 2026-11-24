// Ported from PhoneGo (MIT, Copyright (c) 2024 xuyifan-0731, (c) 2025 yangqin-jiang):
// android-app/.../pairing/AdbShell.kt. Changes: one connection is kept and each command opens its
// own shell stream on it (PhoneGo connected and did the TLS handshake for every command); stdout,
// stderr and the exit code come back separately.
package app.momoding.phone.adb

import android.annotation.SuppressLint
import android.util.Base64
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * A connection to this phone's own adbd (wireless debugging), authenticated with the identity
 * [AdbPairing] stored. Commands run one at a time, each on its own `shell,v2` stream.
 */
class AdbConnection private constructor(private val socket: Socket, private val input: DataInputStream, private val output: OutputStream) {

    class Result(val exitCode: Int, val stdout: String, val stderr: String)

    private var nextId = 1

    @Synchronized
    fun shell(command: String, timeoutMs: Int): Result {
        val localId = nextId++
        socket.soTimeout = timeoutMs
        output.write(frame(OPEN, localId, 0, "shell,v2,raw:$command\u0000".toByteArray()))
        output.flush()
        val stdout = java.io.ByteArrayOutputStream()
        val stderr = java.io.ByteArrayOutputStream()
        var exitCode = -1
        var remoteId = 0
        while (true) {
            val f = read(input)
            if (f.arg1 != localId && f.command != CNXN) continue // a late frame of an earlier stream
            when (f.command) {
                OKAY -> remoteId = f.arg0
                WRTE -> {
                    remoteId = f.arg0
                    var at = 0
                    while (at + 5 <= f.data.size) {
                        val id = f.data[at].toInt() and 0xFF
                        val len = ByteBuffer.wrap(f.data, at + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        if (at + 5 + len > f.data.size) break
                        when (id) {
                            1 -> stdout.write(f.data, at + 5, len)
                            2 -> stderr.write(f.data, at + 5, len)
                            3 -> if (len > 0) exitCode = f.data[at + 5].toInt() and 0xFF
                        }
                        at += 5 + len
                    }
                    output.write(frame(OKAY, localId, remoteId)); output.flush()
                }
                CLSE -> {
                    if (remoteId != 0) { output.write(frame(CLSE, localId, remoteId)); output.flush() }
                    return Result(exitCode, stdout.toString(Charsets.UTF_8.name()), stderr.toString(Charsets.UTF_8.name()))
                }
                else -> throw IllegalStateException("unexpected frame 0x%08x".format(f.command))
            }
        }
    }

    fun close() { runCatching { socket.close() } }

    val isOpen: Boolean get() = !socket.isClosed

    companion object {
        private const val A_VERSION = 0x01000001
        private const val A_STLS_VERSION = 0x01000000
        private const val A_MAXDATA = 1048576
        private const val CNXN = 0x4E584E43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4E45504F
        private const val OKAY = 0x59414B4F
        private const val CLSE = 0x45534C43
        private const val WRTE = 0x45545257
        private const val STLS = 0x534c5453
        private const val FEATURES = "shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,abb_exec"

        /**
         * Connects to adbd on [port] (loopback, then [fallbackHost]) and finishes the handshake:
         * plain CNXN, the device answers STLS, TLS 1.3 with ALPN "adb", then its CNXN banner.
         */
        fun open(certPem: ByteArray, keyPem: ByteArray, port: Int, fallbackHost: String?, timeoutMs: Int = 8000): AdbConnection {
            var last: Throwable? = null
            for (host in listOfNotNull("127.0.0.1", fallbackHost)) {
                try { return open(certPem, keyPem, host, port, timeoutMs) } catch (t: Throwable) { last = t }
            }
            throw last ?: IllegalStateException("no address")
        }

        private fun open(certPem: ByteArray, keyPem: ByteArray, host: String, port: Int, timeoutMs: Int): AdbConnection {
            val tls = context(certPem, keyPem) // built first: adbd gives up quickly once STLS is sent
            val raw = Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(host, port), timeoutMs); soTimeout = timeoutMs }
            try {
                raw.getOutputStream().write(frame(CNXN, A_VERSION, A_MAXDATA, "host::features=$FEATURES\u0000".toByteArray()))
                raw.getOutputStream().flush()
                val hello = read(DataInputStream(raw.getInputStream()))
                if (hello.command == CNXN) return AdbConnection(raw, DataInputStream(raw.getInputStream()), raw.getOutputStream())
                check(hello.command == STLS) { "expected STLS, got 0x%08x".format(hello.command) }
                raw.getOutputStream().write(frame(STLS, A_STLS_VERSION, 0)); raw.getOutputStream().flush()
                raw.soTimeout = 0 // the TLS handshake expects blocking reads
                val ssl = tls.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                ssl.useClientMode = true
                runCatching { ssl.enabledProtocols = arrayOf("TLSv1.3") }
                ssl.sslParameters = ssl.sslParameters.apply { applicationProtocols = arrayOf("adb") }
                ssl.startHandshake()
                ssl.soTimeout = timeoutMs
                val input = DataInputStream(ssl.inputStream)
                // after the upgrade the device speaks first; a second CNXN from us would restart TLS
                val banner = read(input)
                check(banner.command != AUTH) { "this phone does not know Momoding's key: pair again" }
                check(banner.command == CNXN) { "expected CNXN, got 0x%08x".format(banner.command) }
                return AdbConnection(ssl, input, ssl.outputStream)
            } catch (t: Throwable) {
                runCatching { raw.close() }
                throw t
            }
        }

        /** True when something on [port] answers a plain CNXN with STLS: wireless debugging's adbd. */
        fun speaksAdb(port: Int): Boolean = runCatching {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().write(frame(CNXN, A_VERSION, A_MAXDATA)); s.getOutputStream().flush()
                read(DataInputStream(s.getInputStream())).command == STLS
            }
        }.getOrDefault(false)

        private class Frame(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

        private fun frame(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)): ByteArray {
            val crc = java.util.zip.CRC32().apply { update(data) }.value.toInt()
            return ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size).putInt(crc).putInt(command.inv()).put(data).array()
        }

        private fun read(input: DataInputStream): Frame {
            val header = ByteArray(24)
            input.readFully(header)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = bb.int; val arg0 = bb.int; val arg1 = bb.int
            val len = bb.int; bb.int; val magic = bb.int
            check(command.inv() == magic) { "bad magic" }
            val data = ByteArray(len)
            if (len > 0) try { input.readFully(data) } catch (e: EOFException) { throw IllegalStateException("connection closed mid-frame", e) }
            return Frame(command, arg0, arg1, data)
        }

        private fun der(pem: ByteArray, label: String): ByteArray = Base64.decode(
            String(pem).substringAfter("-----BEGIN $label-----").substringBefore("-----END $label-----").replace(Regex("\\s"), ""),
            Base64.DEFAULT,
        )

        // Trust all, deliberately: the peer is adbd on this same phone with a per-pairing
        // certificate; what authenticates is the key it stored when the code was entered.
        @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
        private fun context(certPem: ByteArray, keyPem: ByteArray): SSLContext {
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(der(certPem, "CERTIFICATE").inputStream()) as X509Certificate
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der(keyPem, "PRIVATE KEY")))
            val km = object : X509ExtendedKeyManager() {
                override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)
                override fun getPrivateKey(alias: String?): PrivateKey = key
                override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? =
                    if (keyType == null || keyType.equals("RSA", true)) arrayOf("m") else null
                override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?): String? =
                    if (keyType == null || keyType.any { it.equals("RSA", true) }) "m" else null
                override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
                override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? = null
            }
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            return SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(km), trustAll, null) }
        }
    }
}
