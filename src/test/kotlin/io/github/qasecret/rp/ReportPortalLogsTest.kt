package io.github.qasecret.rp

import io.github.qasecret.rp.support.RecordingReportPortalClient
import io.github.qasecret.rp.support.RecordingReportPortalClient.Companion.reportPortal
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.TestEngineLauncher
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Offline verification (recording client) that the public [ReportPortalLogs] API binds logs emitted
 * from inside a test body to that test's ReportPortal item, including the multipart file-attachment
 * path (a live end-to-end variant is in [LiveLogsReportPortalTest]).
 */
class ReportPortalLogsTest : FunSpec({

    test("logs emitted inside a test body are bound to that test's item") {
        val client = RecordingReportPortalClient()
        val extension = ReportPortalExtension(reportPortal(client), RpConfig())
        val projectConfig = object : AbstractProjectConfig() {
            override val extensions: List<Extension> = listOf(extension)
        }

        TestEngineLauncher().withProjectConfig(projectConfig).withClasses(LoggingSpec::class).launch()

        val loggedTest = client.started("logs things")
        val logs = client.logsFor(loggedTest.uuid)
        logs.map { it.level } shouldBe listOf("INFO", "WARN")
        logs.first().message shouldContain "hello from the test"

        // The sibling test logged nothing, so no logs should be attributed to it.
        client.logsFor(client.started("logs nothing").uuid) shouldHaveSize 0
    }

    test("a file attached from a test body is uploaded via the multipart channel, bound to its item") {
        val client = RecordingReportPortalClient()
        val extension = ReportPortalExtension(reportPortal(client), RpConfig())
        val projectConfig = object : AbstractProjectConfig() {
            override val extensions: List<Extension> = listOf(extension)
        }

        TestEngineLauncher().withProjectConfig(projectConfig).withClasses(AttachingSpec::class).launch()

        val attachingTest = client.started("attaches a file")
        // Attachments go through the multipart batch channel (not the plain log(SaveLogRQ) channel) and
        // are flushed when the launch finishes — so by the time launch() returns, the batch is recorded.
        client.multipartBatches.shouldNotBeEmpty()
        val rendered = client.multipartBatches.joinToString("\n")
        rendered shouldContain attachingTest.uuid // the SaveLogRQ JSON part carries the resolved item UUID
        rendered shouldContain "evidence.txt"     // file name
        rendered shouldContain "see the attached file" // log message
        rendered shouldContain "the-file-bytes"    // raw file content (binary part)
        // The attachment is not double-reported on the plain text-log channel.
        client.logsFor(attachingTest.uuid) shouldHaveSize 0
    }
}) {
    class LoggingSpec : FunSpec({
        test("logs things") {
            ReportPortalLogs.info("hello from the test")
            ReportPortalLogs.warn("a warning")
        }
        test("logs nothing") {
            1 shouldBe 1
        }
    })

    class AttachingSpec : FunSpec({
        test("attaches a file") {
            ReportPortalLogs.attach(
                name = "evidence.txt",
                content = "the-file-bytes".toByteArray(),
                contentType = "text/plain",
                message = "see the attached file",
            )
        }
    })
}
