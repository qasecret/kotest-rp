package io.github.qasecret.rp.logback

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import com.epam.reportportal.listeners.LogLevel
import io.github.qasecret.rp.ReportPortalLogs

/**
 * Logback appender that forwards ordinary SLF4J/logback events to ReportPortal, attaching each to the
 * Kotest test currently running. Your test code logs the usual way (`LoggerFactory.getLogger(...)`)
 * with no ReportPortal-specific calls.
 *
 * Register it in `logback.xml` / `logback-test.xml`:
 * ```xml
 * <appender name="REPORTPORTAL" class="io.github.qasecret.rp.logback.ReportPortalLogbackAppender"/>
 * <root level="INFO">
 *   <appender-ref ref="REPORTPORTAL"/>
 * </root>
 * ```
 *
 * Attribution is resolved through [ReportPortalLogs], which reads the item key that
 * [io.github.qasecret.rp.ReportPortalExtension] publishes to SLF4J MDC ([io.github.qasecret.rp.RpMdc]).
 * Because that key travels with the test coroutine, logs attach to the right item even across
 * coroutine thread-hops and under concurrent execution. Events emitted outside any test (or from a
 * thread with no active test context) are no-ops. ReportPortal's own loggers are skipped to avoid
 * feedback loops.
 */
class ReportPortalLogbackAppender : AppenderBase<ILoggingEvent>() {

    override fun append(event: ILoggingEvent) {
        val name = event.loggerName ?: ""
        if (name.startsWith("com.epam.reportportal") || name.startsWith("io.github.qasecret.rp")) return

        val message = buildString {
            append(event.formattedMessage)
            event.throwableProxy?.let { append('\n').append(ThrowableProxyUtil.asString(it)) }
        }
        ReportPortalLogs.log(toReportPortalLevel(event.level), message)
    }

    private fun toReportPortalLevel(level: Level): LogLevel = when (level.toInt()) {
        Level.ERROR_INT -> LogLevel.ERROR
        Level.WARN_INT -> LogLevel.WARN
        Level.INFO_INT -> LogLevel.INFO
        Level.DEBUG_INT -> LogLevel.DEBUG
        Level.TRACE_INT -> LogLevel.TRACE
        else -> LogLevel.INFO
    }
}
