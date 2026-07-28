package app.momoding.core.transport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.auth.HostBindingSnapshot
import app.momoding.core.auth.HostBindingStatus
import app.momoding.core.auth.HostStateStore
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.HostTaskSummary
import app.momoding.core.data.RoomTaskListMerger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BindingScopedTaskListPublisherTest {
    private lateinit var database: MomodingDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun staleInFlightListCannotPublishAfterUnpairClear() = runTest {
        val state = MemoryStateStore(activeSnapshot())
        val bindingMutex = Mutex()
        var leaseCurrent = true
        val publisher = BindingScopedTaskListPublisher(
            bindingMutex,
            state,
            leaseIsCurrent = { leaseCurrent },
            RoomTaskListMerger(database),
        )
        val requestStarted = CompletableDeferred<Unit>()
        val finalPage = CompletableDeferred<app.momoding.wire.ReceivedP1bServerFrame>()
        val port = TaskListWirePort { _, _ ->
            requestStarted.complete(Unit)
            finalPage.await()
        }
        val synchronizing = async {
            runCatching { TaskListSynchronizer(port, publisher).synchronize() }
        }
        requestStarted.await()

        bindingMutex.withLock {
            state.snapshot = state.snapshot?.copy(status = HostBindingStatus.UNPAIRING)
            leaseCurrent = false
            database.clearAllTables()
            state.snapshot = null
        }
        finalPage.complete(taskListPage())

        assertTrue(synchronizing.await().isFailure)
        assertEquals(emptyList<Any>(), database.momodingDao().allTasks())
    }

    private class MemoryStateStore(var snapshot: HostBindingSnapshot?) : HostStateStore {
        override suspend fun read(): HostBindingSnapshot? = snapshot
        override suspend fun write(snapshot: HostBindingSnapshot) {
            this.snapshot = snapshot
        }
        override suspend fun clear() {
            snapshot = null
        }
    }

    private fun activeSnapshot() = HostBindingSnapshot(
        status = HostBindingStatus.ACTIVE,
        endpoint = "https://host.example:8443",
        spkiPin = "sha256/BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ=",
        clientInstanceId = "11111111-1111-4111-8111-111111111111",
        deviceId = "22222222-2222-4222-8222-222222222222",
        deviceName = "Android",
        hostId = "33333333-3333-4333-8333-333333333333",
        credentialId = "44444444-4444-4444-8444-444444444444",
        updatedAtMillis = 1,
    )

    private fun taskListPage(): app.momoding.wire.ReceivedP1bServerFrame =
        app.momoding.wire.ReliabilityContractDecoder.decode(
            """{"protocolVersion":1,"kind":"response","requestId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","ok":true,"data":{"tasks":[{"taskId":"55555555-5555-4555-8555-555555555555","title":"Old Host","updatedAt":"1970-01-01T00:00:01.000Z","runState":"idle","recoveryState":"normal","snapshotVersion":1}],"listRevision":1}}""",
        )
}
