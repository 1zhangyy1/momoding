package app.momoding.debug

import android.content.Context
import app.momoding.core.runtime.local.PiRuntimeBootstrapProof
import app.momoding.core.runtime.local.PhoneLocalPiEngine
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

class PhoneLocalPiSmokeRunner(
    context: Context,
) : AutoCloseable {
    private val assets = context.applicationContext.assets
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phone-local-pi-runtime")
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    suspend fun run(iterations: Int = 20): List<PiRuntimeBootstrapProof> {
        require(iterations > 0)
        return withContext(dispatcher) {
            List(iterations) {
                val engine = PhoneLocalPiEngine(assets, dispatcher)
                try {
                    engine.bootstrap()
                } finally {
                    engine.shutdown()
                }
            }
        }
    }

    override fun close() {
        dispatcher.close()
    }
}
