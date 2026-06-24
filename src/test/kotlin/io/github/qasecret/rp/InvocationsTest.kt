package io.github.qasecret.rp

import io.github.qasecret.rp.support.RecordingReportPortalClient
import io.github.qasecret.rp.support.RecordingReportPortalClient.Companion.reportPortal
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.TestEngineLauncher
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Offline verification of flaky-detection support: multi-invocation tests get an `invocations`
 * attribute and a per-invocation log marker on their item. (Kotest 5.9 exposes no per-invocation
 * result, so we report the count and markers, not RP retry groups.)
 */
class InvocationsTest : FunSpec({

    test("invocations are surfaced as an attribute and per-invocation log markers") {
        val client = RecordingReportPortalClient()
        val extension = ReportPortalExtension(reportPortal(client), RpConfig())
        val projectConfig = object : AbstractProjectConfig() {
            override val extensions: List<Extension> = listOf(extension)
        }

        TestEngineLauncher().withProjectConfig(projectConfig).withClasses(InvocationsSpec::class).launch()

        val item = client.started("runs three times")
        item.rq.attributes.any { it.key == "invocations" && it.value == "3" } shouldBe true

        val logs = client.logsFor(item.uuid)
        logs shouldHaveSize 3
        logs.all { it.level == "INFO" && it.message.contains("of 3") } shouldBe true

        // A single-invocation test gets neither the attribute nor invocation markers.
        val single = client.started("runs once")
        single.rq.attributes.none { it.key == "invocations" } shouldBe true
        client.logsFor(single.uuid) shouldHaveSize 0
    }
}) {
    class InvocationsSpec : FunSpec({
        test("runs three times").config(invocations = 3) { 1 shouldBe 1 }
        test("runs once") { 1 shouldBe 1 }
    })
}
