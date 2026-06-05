package io.github.qasecret.rp.internal

import io.github.qasecret.rp.RpMdc
import kotlinx.coroutines.ThreadContextElement
import org.slf4j.MDC
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-context element that pins the current test's ReportPortal item key into SLF4J [MDC] for as
 * long as the test's coroutine is running on a thread — re-applied on every resume and restored on
 * every suspend. Because it travels with the coroutine context, the key:
 *  - survives coroutine thread-hops (`withContext(Dispatchers.IO) { ... }`), and
 *  - stays per-test under concurrent execution (each test's coroutine carries its own element),
 *
 * which a plain `ThreadLocal` cannot. [io.github.qasecret.rp.internal.RpLog] resolves logs by this MDC
 * value, so both [io.github.qasecret.rp.ReportPortalLogs] and the logback appender attribute correctly.
 *
 * This intentionally mirrors `kotlinx.coroutines.slf4j.MDCContext` but for a single, library-owned key,
 * so kotest-rp need not add the `kotlinx-coroutines-slf4j` dependency.
 */
internal class RpItemContextElement(private val itemKey: String) :
    ThreadContextElement<String?>, AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<RpItemContextElement>

    override fun updateThreadContext(context: CoroutineContext): String? {
        val previous = MDC.get(RpMdc.ITEM_KEY)
        MDC.put(RpMdc.ITEM_KEY, itemKey)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: String?) {
        if (oldState == null) MDC.remove(RpMdc.ITEM_KEY) else MDC.put(RpMdc.ITEM_KEY, oldState)
    }
}
