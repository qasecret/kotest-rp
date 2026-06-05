package io.github.qasecret.rp

import io.github.qasecret.rp.support.RecordingReportPortalClient
import io.github.qasecret.rp.support.RecordingReportPortalClient.Companion.reportPortal
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.TestEngineLauncher
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Offline verification (recording client) that the public [ReportPortalLogs] API binds logs emitted
 * from inside a test body to that test's ReportPortal item. (Binary attachments use the multipart
 * path and are verified live in [LiveLogsReportPortalTest].)
 */
class ReportPortalLogsTest : FunSpec({

    test("logs emitted inside a test body are bound to that test's item") {
        val client = RecordingReportPortalClient()
        val extension = ReportPortalExtension(reportPortal(client), RpConfig())
        val projectConfig = object : AbstractProjectConfig() {
            override fun extensions(): List<Extension> = listOf(extension)
        }

        TestEngineLauncher().withProjectConfig(projectConfig).withClasses(LoggingSpec::class).launch()

        val loggedTest = client.started("logs things")
        val logs = client.logsFor(loggedTest.uuid)
        logs.map { it.level } shouldBe listOf("INFO", "WARN")
        logs.first().message shouldContain "hello from the test"

        // The sibling test logged nothing, so no logs should be attributed to it.
        client.logsFor(client.started("logs nothing").uuid) shouldHaveSize 0
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
}
