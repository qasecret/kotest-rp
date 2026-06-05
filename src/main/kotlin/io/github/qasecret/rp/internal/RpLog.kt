package io.github.qasecret.rp.internal

import com.epam.reportportal.listeners.ListenerParameters
import com.epam.reportportal.listeners.LogLevel
import com.epam.reportportal.service.LoggingContext
import com.epam.reportportal.service.ReportPortal
import com.epam.reportportal.service.ReportPortalClient
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ
import io.reactivex.Maybe
import io.reactivex.schedulers.Schedulers
import org.slf4j.LoggerFactory
import java.util.Date

/** Everything needed to send a log bound to one ReportPortal item. */
internal class RpLogContext(
    val client: ReportPortalClient,
    val launchUuid: Maybe<String>?,
    val itemId: Maybe<String>,
    val params: ListenerParameters,
)

/**
 * Tracks the ReportPortal item for the test currently executing on each thread, so the public
 * [io.github.qasecret.rp.ReportPortalLogs] API can attach logs/files to it from inside a test body.
 *
 * Uses a per-thread stack (Kotest runs a test's before/body/after on one thread under the default
 * thread-affine dispatcher). Emission goes through a per-call [LoggingContext] on the calling thread,
 * which is the supported path for binary (multipart) attachments and is correct because the call
 * happens on the test-body thread.
 */
internal object RpLog {

    private val logger = LoggerFactory.getLogger(RpLog::class.java)
    private val stack = ThreadLocal.withInitial { ArrayDeque<RpLogContext>() }

    fun push(context: RpLogContext) = stack.get().addLast(context)

    /** Removes the context for [itemId] from the current thread's stack (best-effort, exception-safe). */
    fun remove(itemId: Maybe<String>) {
        stack.get().removeAll { it.itemId === itemId }
    }

    fun clear() = stack.get().clear()

    private fun current(): RpLogContext? = stack.get().lastOrNull()

    /** Sends a log (optionally with a file attachment) to the current test item; no-op if none. */
    fun emit(level: LogLevel, message: String, file: SaveLogRQ.File?) {
        val ctx = current() ?: run {
            logger.debug("No active ReportPortal test item on this thread; log dropped")
            return
        }
        try {
            if (file == null) emitText(ctx, level, message) else emitAttachment(ctx, level, message, file)
        } catch (e: Exception) {
            logger.debug("Failed to emit ReportPortal log/attachment", e)
        }
    }

    /** Plain text log: posted directly with explicit UUIDs (deterministic, thread-safe, no context). */
    private fun emitText(ctx: RpLogContext, level: LogLevel, message: String) {
        val uuid = ctx.itemId.blockingGet() ?: return
        val rq = SaveLogRQ().apply {
            itemUuid = uuid
            this.message = message
            this.level = level.name
            logTime = Date()
            ctx.launchUuid?.blockingGet()?.let { launchUuid = it }
        }
        ctx.client.log(rq).blockingGet()
    }

    /**
     * File attachment: binaries must be uploaded via ReportPortal's multipart path, which goes through
     * a [LoggingContext]. Safe here because this runs on the test-body thread for the current item.
     */
    private fun emitAttachment(ctx: RpLogContext, level: LogLevel, message: String, file: SaveLogRQ.File) {
        LoggingContext.init(ctx.launchUuid ?: Maybe.empty(), ctx.itemId, ctx.client, Schedulers.io(), ctx.params)
        try {
            ReportPortal.emitLog { itemUuid ->
                SaveLogRQ().apply {
                    this.itemUuid = itemUuid
                    this.message = message
                    this.level = level.name
                    logTime = Date()
                    this.file = file
                }
            }
        } finally {
            LoggingContext.complete().blockingAwait()
        }
    }
}
