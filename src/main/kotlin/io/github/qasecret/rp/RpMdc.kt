package io.github.qasecret.rp

/**
 * SLF4J [org.slf4j.MDC] key under which [ReportPortalExtension] publishes the currently-running test's
 * ReportPortal item key (see `RpMapper.testKey`). The value is propagated into the test coroutine so
 * it stays correct across coroutine thread-hops (`withContext`) and concurrent tests.
 *
 * The bundled [io.github.qasecret.rp.logback.ReportPortalLogbackAppender] reads this to attach logback
 * events to the right item. If you use a different logging backend (log4j2, etc.), write a small
 * appender that, for each event, calls [ReportPortalLogs.log] — it resolves the same MDC value — or
 * read [ITEM_KEY] yourself.
 */
object RpMdc {
    const val ITEM_KEY: String = "rpItemKey"
}
