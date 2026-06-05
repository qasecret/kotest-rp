package io.github.qasecret.rp.internal

import com.epam.reportportal.listeners.ListenerParameters
import com.epam.reportportal.listeners.LogLevel
import com.epam.reportportal.service.LoggingContext
import com.epam.reportportal.service.ReportPortal
import com.epam.reportportal.service.ReportPortalClient
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ
import io.github.qasecret.rp.RpMdc
import io.reactivex.Maybe
import io.reactivex.schedulers.Schedulers
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/** Everything needed to send a log bound to one ReportPortal item. */
internal class RpLogContext(
    val client: ReportPortalClient,
    val launchUuid: Maybe<String>?,
    val itemId: Maybe<String>,
    val params: ListenerParameters,
)

/**
 * Tracks the ReportPortal item for the test currently executing, so the public
 * [io.github.qasecret.rp.ReportPortalLogs] API (and the bundled logback appender) can attach logs and
 * files to it from inside a test body.
 *
 * The current item is resolved by the test's item key, which [ReportPortalExtension] publishes to
 * SLF4J [MDC] ([RpMdc.ITEM_KEY]) via [RpItemContextElement]. Because the key rides the test coroutine,
 * resolution stays correct across coroutine thread-hops and under concurrent execution. Emission of
 * binary (multipart) attachments goes through a per-call [LoggingContext] on the calling thread, which
 * is the supported path and is correct because the call happens on the test-body thread.
 */
internal object RpLog {

    private val logger = LoggerFactory.getLogger(RpLog::class.java)

    /** Item key (see `RpMapper.testKey`) -> context, resolved via the SLF4J [MDC] value. */
    private val byKey = ConcurrentHashMap<String, RpLogContext>()

    /** Registers [context] under its test item [key] so logs can be resolved via the MDC value. */
    fun register(key: String, context: RpLogContext) { byKey[key] = context }

    /** Removes the per-key registration (best-effort, exception-safe). */
    fun unregister(key: String) { byKey.remove(key) }

    fun clear() = byKey.clear()

    /** Resolves the target item via the MDC item key published by [RpItemContextElement]. */
    private fun current(): RpLogContext? =
        MDC.get(RpMdc.ITEM_KEY)?.let { key -> byKey[key] }

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
