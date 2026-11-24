package app.momoding.phone

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The phone shell's resident half: a process started once through wireless debugging
 * (`app_process` with Momoding's APK on the class path, as Shizuku and PhoneGo start theirs) that
 * keeps the shell's rights after wireless debugging goes off. Android switches wireless debugging
 * off on any Wi-Fi change ("Detected wifi network change. Disabling adbwifi", measured on a
 * Xiaomi 12X mid-task), and many phones on every restart; this process lives until the restart.
 *
 * It listens on a loopback port and runs one command per connection, only when the request carries
 * the secret the app started it with (SELinux keeps an app from connecting to a shell-owned unix
 * socket, so no peer credentials; TCP on 127.0.0.1 is open to the app). A request is a
 * length-prefixed JSON {"token", "command", "timeoutMs"}; the answer {"exit", "stdout", "stderr"}.
 */
object ShellServer {
    /** Bumped when the protocol or the server changes, so an old resident one is replaced. */
    const val VERSION = 2

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toIntOrNull() ?: return
        val token = args.getOrNull(1) ?: return
        val server = try { ServerSocket(port, 16, InetAddress.getByName("127.0.0.1")) } catch (e: Exception) { return }
        while (true) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            thread(isDaemon = true) { runCatching { serve(client, token) } }
        }
    }

    private fun serve(client: Socket, token: String) = client.use { s ->
        val input = DataInputStream(s.inputStream); val output = DataOutputStream(s.outputStream)
        val size = input.readInt().takeIf { it in 1..1_000_000 } ?: return
        val request = JSONObject(String(ByteArray(size).also { input.readFully(it) }))
        if (request.optString("token") != token) return
        val reply = if (request.optBoolean("ping")) JSONObject().put("version", VERSION) else run(request.getString("command"), request.optLong("timeoutMs", 30_000))
        val bytes = reply.toString().toByteArray()
        output.writeInt(bytes.size); output.write(bytes); output.flush()
    }

    private fun run(command: String, timeoutMs: Long): JSONObject {
        val p = ProcessBuilder("sh", "-c", command).start()
        val out = StringBuilder(); val err = StringBuilder()
        val readers = listOf(thread { out.append(p.inputStream.bufferedReader().readText()) }, thread { err.append(p.errorStream.bufferedReader().readText()) })
        val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) p.destroyForcibly()
        readers.forEach { it.join(1_000) }
        return JSONObject().put("exit", if (done) p.exitValue() else 124).put("stdout", out.toString()).put("stderr", if (done) err.toString() else "timed out")
    }

    // ── the app's side ──

    /** Sends [request] to the resident server at [port]; null when it is not running (or not ours). */
    fun call(port: Int, token: String, request: JSONObject, timeoutMs: Long): JSONObject? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 1_000)
            s.soTimeout = (timeoutMs + 5_000).toInt()
            val out = DataOutputStream(s.outputStream); val bytes = request.put("token", token).toString().toByteArray()
            out.writeInt(bytes.size); out.write(bytes); out.flush()
            val input = DataInputStream(s.inputStream)
            JSONObject(String(ByteArray(input.readInt()).also { input.readFully(it) }))
        }
    }.getOrNull()

    /** The command that starts the resident server from a shell, detached from that shell. */
    fun startCommand(apk: String, port: Int, token: String): String =
        "CLASSPATH=$apk setsid app_process /system/bin ${ShellServer::class.java.name} $port $token </dev/null >/dev/null 2>&1 &"
}
