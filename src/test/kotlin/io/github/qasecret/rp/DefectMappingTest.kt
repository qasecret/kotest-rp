package io.github.qasecret.rp

import io.github.qasecret.rp.support.RecordingReportPortalClient
import io.github.qasecret.rp.support.RecordingReportPortalClient.Companion.reportPortal
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.test.TestResult
import io.kotest.engine.TestEngineLauncher
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** Offline verification of configurable defect typing on failed tests. */
class DefectMappingTest : FunSpec({

    fun run(client: RecordingReportPortalClient, config: RpConfig) {
        val extension = ReportPortalExtension(reportPortal(client), config)
        val projectConfig = object : AbstractProjectConfig() {
            override val extensions: List<Extension> = listOf(extension)
        }
        TestEngineLauncher().withProjectConfig(projectConfig).withClasses(FailuresSpec::class).launch()
    }

    test("resolver maps assertion failures and exceptions to different defect types") {
        val client = RecordingReportPortalClient()
        run(
            client,
            RpConfig(
                defectTypeResolver = { _, result ->
                    if (result is TestResult.Failure) RpDefect.PRODUCT_BUG else RpDefect.AUTOMATION_BUG
                },
            ),
        )

        client.finishOf(client.started("assertion fails").uuid).rq.issue.let {
            it.shouldNotBeNull()
            it.issueType shouldBe RpDefect.PRODUCT_BUG
        }
        client.finishOf(client.started("throws exception").uuid).rq.issue.let {
            it.shouldNotBeNull()
            it.issueType shouldBe RpDefect.AUTOMATION_BUG
        }
    }

    test("resolver returning null produces no defect") {
        val client = RecordingReportPortalClient()
        run(client, RpConfig(defectTypeResolver = { _, _ -> null }))
        client.finishOf(client.started("assertion fails").uuid).rq.issue.shouldBeNull()
    }

    test("default failureDefectType applies when no resolver is set") {
        val client = RecordingReportPortalClient()
        run(client, RpConfig())
        client.finishOf(client.started("assertion fails").uuid).rq.issue?.issueType shouldBe RpDefect.TO_INVESTIGATE
    }

    test("failureDefectType = null disables defects") {
        val client = RecordingReportPortalClient()
        run(client, RpConfig(failureDefectType = null))
        client.finishOf(client.started("assertion fails").uuid).rq.issue.shouldBeNull()
    }
}) {
    class FailuresSpec : FunSpec({
        test("assertion fails") { 1 shouldBe 2 }
        test("throws exception") { throw RuntimeException("kaboom") }
    })
}
