package app.momoding.sandbox

/**
 * The name of the sandbox's offload socket: the Linux abstract socket the app listens on and
 * proot connects to (`--native-offload=`).
 *
 * Abstract sockets share one namespace across every app on the device. OpenMinis binds
 * `native-offload`, so a second app binding the same name fails at startup; ours carries the
 * application id instead, with a per-process fallback for the moment a previous process of
 * ours still holds it. proot is told whichever name was bound, so nothing else changes.
 * Ported from nanoMuse (`io.github.nanomuse.sandbox.OffloadSocketName`, issue #271).
 */
object OffloadSocketName {
    /** What OpenMinis binds; never ours. */
    const val UPSTREAM = "native-offload"

    /** `<applicationId>.native-offload`. */
    fun forApp(applicationId: String): String = "$applicationId.$UPSTREAM"

    /** `<applicationId>.native-offload-<pid>`, used when [forApp] is still held. */
    fun forProcess(applicationId: String, pid: Int): String = "${forApp(applicationId)}-$pid"
}
