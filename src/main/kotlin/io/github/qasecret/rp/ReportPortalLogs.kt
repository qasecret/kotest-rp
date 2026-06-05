package io.github.qasecret.rp

import com.epam.reportportal.listeners.LogLevel
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ
import io.github.qasecret.rp.internal.RpLog
import java.io.File
import java.nio.file.Files

/**
 * Attach logs and files (screenshots, payloads, …) to the ReportPortal item for the test currently
 * running. Call these from inside a test body:
 *
 * ```
 * test("uploads a screenshot") {
 *     ReportPortalLogs.info("about to take a screenshot")
 *     ReportPortalLogs.attach(File("screenshot.png"), message = "login page")
 * }
 * ```
 *
 * Logs are bound to the running test's item. Reporting is best-effort and non-fatal: if no test is
 * active on the calling thread (e.g. a custom multi-threaded dispatcher moved execution off the
 * test's thread), the call is a no-op rather than an error.
 */
object ReportPortalLogs {

    fun info(message: String): Unit = log(LogLevel.INFO, message)
    fun warn(message: String): Unit = log(LogLevel.WARN, message)
    fun error(message: String): Unit = log(LogLevel.ERROR, message)
    fun debug(message: String): Unit = log(LogLevel.DEBUG, message)

    /** Sends a plain log message at [level] to the current test item. */
    fun log(level: LogLevel, message: String): Unit = RpLog.emit(level, message, file = null)

    /** Attaches [file] to the current test item, with an optional [message] and log [level]. */
    fun attach(file: File, message: String? = null, level: LogLevel = LogLevel.INFO) {
        val contentType = runCatching { Files.probeContentType(file.toPath()) }.getOrNull()
            ?: "application/octet-stream"
        attach(file.name, file.readBytes(), contentType, message ?: file.name, level)
    }

    /** Attaches raw [content] bytes (named [name], of [contentType]) to the current test item. */
    fun attach(
        name: String,
        content: ByteArray,
        contentType: String,
        message: String? = null,
        level: LogLevel = LogLevel.INFO,
    ) {
        val attachment = SaveLogRQ.File().apply {
            this.name = name
            this.content = content
            this.contentType = contentType
        }
        RpLog.emit(level, message ?: name, attachment)
    }
}
