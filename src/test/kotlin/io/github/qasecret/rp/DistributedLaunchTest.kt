package io.github.qasecret.rp

import com.epam.reportportal.listeners.ListenerParameters
import io.github.qasecret.rp.support.RecordingReportPortalClient
import io.github.qasecret.rp.support.RecordingReportPortalClient.Companion.reportPortal
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.TestEngineLauncher
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Offline verification of distributed / rerun launch handling:
 *  - rerun parameters propagate to the launch start request,
 *  - an externally-supplied launch UUID attaches to that launch without creating or finishing it
 *    (so CI shards can all contribute to one launch owned by an orchestrator).
 */
class DistributedLaunchTest : FunSpec({

    fun run(client: RecordingReportPortalClient, params: ListenerParameters) {
        val extension = ReportPortalExtension(reportPortal(client, params), RpConfig())
        val projectConfig = object : AbstractProjectConfig() {
            override val extensions: List<Extension> = listOf(extension)
        }
        TestEngineLauncher().withProjectConfig(projectConfig).withClasses(SampleSpec::class).launch()
    }

    test("rerun parameters are propagated to the launch start request") {
        val client = RecordingReportPortalClient()
        run(
            client,
            ListenerParameters().apply {
                launchName = "rerun-launch"
                enable = true
                setRerun(true)
                setRerunOf("previous-launch-uuid")
            },
        )

        val launch = client.launches.first()
        launch.isRerun shouldBe true
        launch.rerunOf shouldBe "previous-launch-uuid"
    }

    test("external launch uuid attaches without creating or finishing the launch") {
        val client = RecordingReportPortalClient()
        run(
            client,
            ListenerParameters().apply {
                launchName = "shard"
                enable = true
                launchUuid = "external-launch-123"
            },
        )

        client.launches.shouldBeEmpty()         // never called startLaunch
        client.finishedLaunches.shouldBeEmpty()  // never called finishLaunch
        client.startedItems.shouldNotBeEmpty()   // but items are still reported to the shared launch
    }
}) {
    class SampleSpec : FunSpec({
        test("a test") { 1 shouldBe 1 }
    })
}
