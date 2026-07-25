package app.momoding.core.runtime.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.momoding.debug.IsolatedExecutionProbeService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IsolatedExecutionProbeInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun isolatedProcessHasNoAppPermissionOrMainDataAndNoWritableWorkspace() {
        val mainMarker = File(context.filesDir, "isolated-execution-main-process-secret-marker.txt").apply {
            writeText("must-not-cross-isolated-boundary")
        }
        val response = requestProbe(mainMarker)

        assertEquals(null, response.getString(IsolatedExecutionProbeService.KEY_PROBE_ERROR))
        assertNotEquals(Process.myUid(), response.getInt(IsolatedExecutionProbeService.KEY_UID))
        assertTrue(response.getBoolean(IsolatedExecutionProbeService.KEY_INTERNET_DENIED))
        assertTrue(response.getBoolean(IsolatedExecutionProbeService.KEY_MAIN_MARKER_DENIED))
        assertFalse(response.getBoolean(IsolatedExecutionProbeService.KEY_WORKSPACE_CREATED))
        assertFalse(response.getBoolean(IsolatedExecutionProbeService.KEY_WORKSPACE_WRITABLE))
        assertFalse(response.getBoolean(IsolatedExecutionProbeService.KEY_COMMAND_STARTED))
        assertFalse(response.getBoolean(IsolatedExecutionProbeService.KEY_COMMAND_FINISHED))
        assertEquals(
            "WORKSPACE_UNAVAILABLE",
            response.getString(IsolatedExecutionProbeService.KEY_COMMAND_ERROR),
        )
    }

    private fun requestProbe(mainMarker: File): Bundle {
        val serviceConnected = CountDownLatch(1)
        val responseReceived = CountDownLatch(1)
        var remote: Messenger? = null
        var response: Bundle? = null
        val reply = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                if (message.what == IsolatedExecutionProbeService.RESPONSE_PROBE) {
                    response = message.data
                    responseReceived.countDown()
                    true
                } else {
                    false
                }
            },
        )
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remote = Messenger(binder)
                serviceConnected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val intent = Intent(context, IsolatedExecutionProbeService::class.java)
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(serviceConnected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            remote!!.send(
                Message.obtain(null, IsolatedExecutionProbeService.REQUEST_PROBE).apply {
                    data = Bundle().apply {
                        putString(
                            IsolatedExecutionProbeService.KEY_MAIN_MARKER_PATH,
                            mainMarker.absolutePath,
                        )
                    }
                    replyTo = reply
                },
            )
            assertTrue(responseReceived.await(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            return requireNotNull(response)
        } finally {
            context.unbindService(connection)
            mainMarker.delete()
        }
    }

    companion object {
        private const val BIND_TIMEOUT_SECONDS = 10L
        private const val PROBE_TIMEOUT_SECONDS = 10L
    }
}
