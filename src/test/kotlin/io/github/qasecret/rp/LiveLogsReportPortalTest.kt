package io.github.qasecret.rp

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.TestEngineLauncher

/**
 * Live smoke test for the [ReportPortalLogs] API: emits logs and uploads a file attachment from
 * inside a test body. Disabled by default; enable with `-Drp.live=true`.
 *
 * `./gradlew test --tests "io.github.qasecret.rp.LiveLogsReportPortalTest" -Drp.live=true`
 */
class LiveLogsReportPortalTest : FunSpec({

    val live = System.getProperty("rp.live") == "true"

    test("reports logs and a file attachment to ReportPortal").config(enabled = live) {
        val extension = ReportPortalExtension()
        val projectConfig = object : AbstractProjectConfig() {
            override val extensions: List<Extension> = listOf(extension)
        }

        TestEngineLauncher()
            .withProjectConfig(projectConfig)
            .withClasses(LoggingAttachmentSpec::class)
            .launch()

        println("[live] logs + attachment reported to ReportPortal")
    }
}) {
    class LoggingAttachmentSpec : FunSpec({
        test("logs and attaches a file") {
            ReportPortalLogs.info("starting the workflow")
            ReportPortalLogs.attach(
                name = "evidence.txt",
                content = "kotest-rp evidence payload".toByteArray(),
                contentType = "text/plain",
                message = "attached evidence",
            )
            ReportPortalLogs.warn("workflow finished")
        }
    })
}
