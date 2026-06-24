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

    /** A log emitted before its item existed, held (with its original timestamp) until the item does. */
    private class BufferedLog(
        val level: LogLevel,
        val message: String,
        val file: SaveLogRQ.File?,
        val time: Date,
    )

    /** Item key (see `RpMapper.testKey`) -> context, resolved via the SLF4J [MDC] value. */
    private val byKey = ConcurrentHashMap<String, RpLogContext>()

    /**
     * Item key -> logs emitted before the item was created. A `withData` data row (and any container)
     * is materialized lazily by `RpReporter`, so logs from its body run before its RP item exists.
     * Rather than drop them (the old behavior), we buffer them here and flush on [register].
     */
    private val buffers = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<BufferedLog>>()

    /**
     * Opens a buffer for a not-yet-created item [key] so logs emitted from its body are retained until
     * the item is materialized (see [register]). Called by `RpReporter` when it defers a container.
     */
    fun beginBuffering(key: String) { buffers.putIfAbsent(key, java.util.concurrent.ConcurrentLinkedQueue()) }

    /** Registers [context] under its test item [key], flushing any logs buffered before it existed. */
    fun register(key: String, context: RpLogContext) {
        byKey[key] = context
        buffers.remove(key)?.forEach { buffered ->
            try {
                send(context, buffered.level, buffered.message, buffered.file, buffered.time)
            } catch (e: Exception) {
                logger.debug("Failed to flush buffered ReportPortal log/attachment", e)
            }
        }
    }

    /** Removes the per-key registration (best-effort, exception-safe). */
    fun unregister(key: String) { byKey.remove(key) }

    fun clear() {
        byKey.clear()
        buffers.clear()
    }

    /** Sends a log (optionally with a file attachment) to the current test item; buffers or drops if none. */
    fun emit(level: LogLevel, message: String, file: SaveLogRQ.File?) {
        val key = MDC.get(RpMdc.ITEM_KEY) ?: run {
            logger.debug("No active ReportPortal test item on this thread; log dropped")
            return
        }
        val ctx = byKey[key]
        if (ctx != null) {
            try {
                send(ctx, level, message, file, Date())
            } catch (e: Exception) {
                logger.debug("Failed to emit ReportPortal log/attachment", e)
            }
            return
        }
        // The item isn't created yet. If it's a deferred (buffering) container, retain the log with its
        // real timestamp; otherwise there is no item to attach to and it is dropped.
        val buffer = buffers[key]
        if (buffer != null) buffer.add(BufferedLog(level, message, file, Date()))
        else logger.debug("No active ReportPortal test item on this thread; log dropped")
    }

    private fun send(ctx: RpLogContext, level: LogLevel, message: String, file: SaveLogRQ.File?, time: Date) {
        if (file == null) emitText(ctx, level, message, time) else emitAttachment(ctx, level, message, file, time)
    }

    /** Plain text log: posted directly with explicit UUIDs (deterministic, thread-safe, no context). */
    private fun emitText(ctx: RpLogContext, level: LogLevel, message: String, time: Date) {
        val uuid = ctx.itemId.blockingGet() ?: return
        val rq = SaveLogRQ().apply {
            itemUuid = uuid
            this.message = message
            this.level = level.name
            logTime = time
            ctx.launchUuid?.blockingGet()?.let { launchUuid = it }
        }
        ctx.client.log(rq).blockingGet()
    }

    /**
     * File attachment: binaries must be uploaded via ReportPortal's multipart path, which goes through
     * a [LoggingContext]. Safe here because this runs on the test-body thread for the current item.
     */
    private fun emitAttachment(ctx: RpLogContext, level: LogLevel, message: String, file: SaveLogRQ.File, time: Date) {
        LoggingContext.init(ctx.launchUuid ?: Maybe.empty(), ctx.itemId, ctx.client, Schedulers.io(), ctx.params)
        try {
            ReportPortal.emitLog { itemUuid ->
                SaveLogRQ().apply {
                    this.itemUuid = itemUuid
                    this.message = message
                    this.level = level.name
                    logTime = time
                    this.file = file
                }
            }
        } finally {
            LoggingContext.complete().blockingAwait()
        }
    }
}
