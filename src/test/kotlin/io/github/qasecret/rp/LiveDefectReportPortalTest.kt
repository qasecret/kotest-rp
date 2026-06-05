package io.github.qasecret.rp

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestResult
import io.kotest.engine.TestEngineLauncher
import io.kotest.matchers.shouldBe

/**
 * Live demo of configurable defect typing: assertion failures -> product bug, exceptions ->
 * automation bug. Disabled by default; enable with `-Drp.live=true`.
 *
 * `./gradlew test --tests "io.github.qasecret.rp.LiveDefectReportPortalTest" -Drp.live=true`
 */
class LiveDefectReportPortalTest : FunSpec({

    val live = System.getProperty("rp.live") == "true"

    test("maps assertion vs exception failures to different defect types").config(enabled = live) {
        val config = RpConfig(
            defectTypeResolver = { _, result ->
                if (result is TestResult.Failure) RpDefect.PRODUCT_BUG else RpDefect.AUTOMATION_BUG
            },
        )
        val extension = ReportPortalExtension(config)
        val projectConfig = object : AbstractProjectConfig() {
            override fun extensions(): List<Extension> = listOf(extension)
        }

        TestEngineLauncher().withProjectConfig(projectConfig).withClasses(DefectSpec::class).launch()

        println("[live] defect-typed failures reported to ReportPortal")
    }
}) {
    class DefectSpec : FunSpec({
        test("assertion failure -> product bug") { 1 shouldBe 2 }
        test("exception -> automation bug") { throw RuntimeException("boom from kotest-rp") }
    })
}
