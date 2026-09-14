package ai.rever.boss.kernel

import ai.rever.boss.ipc.proto.RepairAction
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/** A dead adviser must not terminate the collector responsible for later process failures. */
internal suspend fun repairAdviceOrNull(
    processId: String,
    request: suspend () -> RepairAction?,
): RepairAction? =
    try {
        request().also {
            if (it == null) {
                LoggerFactory.getLogger("RepairAdvice").warn("No repair advice for {}; recovering without it", processId)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        LoggerFactory.getLogger("RepairAdvice").warn(
            "Repair adviser unavailable for {} ({}); recovering without advice",
            processId,
            failure.javaClass.simpleName,
        )
        null
    }
