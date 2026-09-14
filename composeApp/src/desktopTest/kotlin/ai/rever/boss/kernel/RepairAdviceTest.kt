package ai.rever.boss.kernel

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.proto.RepairAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RepairAdviceTest {
    @Test
    fun `a retained dead client does not stop later recovery`() =
        runBlocking {
            // Closing before first connection needs no live endpoint or certificate.
            val retained = BossIpcClient("tcp://127.0.0.1:1", IpcClientCredentials("unused", "0".repeat(64)))
            retained.shutdown()
            val results = mutableListOf<RepairAction?>()
            for (failure in listOf("first", "later")) {
                results +=
                    repairAdviceOrNull(failure) {
                        if (failure == "first") retained.channel
                        RepairAction.newBuilder().setDescription(failure).build()
                    }
            }
            assertNull(results.first())
            assertEquals("later", results.last()?.description)
        }

    @Test
    fun `caller cancellation is not converted to missing advice`() =
        runBlocking {
            assertFailsWith<CancellationException> {
                repairAdviceOrNull("cancelled") { throw CancellationException("cancelled") }
            }
        }
}
