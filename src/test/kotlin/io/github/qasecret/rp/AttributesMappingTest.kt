package io.github.qasecret.rp

import io.github.qasecret.rp.support.RecordingReportPortalClient
import io.github.qasecret.rp.support.RecordingReportPortalClient.Companion.reportPortal
import io.kotest.core.NamedTag
import io.kotest.core.annotation.Tags
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestCaseSeverityLevel
import io.kotest.engine.TestEngineLauncher
import io.kotest.matchers.shouldBe

/**
 * Offline verification (recording client) of attribute mapping and launch agent attributes:
 *  - test tags `key:value` -> keyed attributes, plain tags -> value-only,
 *  - severity -> `severity=<LEVEL>`,
 *  - spec `@Tags` -> `tag` attributes,
 *  - launch carries the `agent` system attribute.
 */
class AttributesMappingTest : FunSpec({

    fun run(client: RecordingReportPortalClient) {
        val extension = ReportPortalExtension(reportPortal(client), RpConfig())
        val projectConfig = object : AbstractProjectConfig() {
            override fun extensions(): List<Extension> = listOf(extension)
        }
        TestEngineLauncher().withProjectConfig(projectConfig).withClasses(TaggedSeveritySpec::class).launch()
    }

    test("launch carries the agent system attribute") {
        val client = RecordingReportPortalClient()
        run(client)
        val attrs = client.launches.first().attributes
        attrs.any { it.key == "agent" && it.value.startsWith("kotest-rp|") } shouldBe true
    }

    test("test attributes include parsed tags and severity") {
        val client = RecordingReportPortalClient()
        run(client)
        val attrs = client.started("tagged test").rq.attributes

        attrs.any { it.key == "team" && it.value == "payments" } shouldBe true // key:value tag
        attrs.any { it.key == null && it.value == "fast" } shouldBe true        // plain tag -> value-only
        attrs.any { it.key == "severity" && it.value == "CRITICAL" } shouldBe true
    }

    test("spec @Tags become tag attributes on the spec SUITE") {
        val client = RecordingReportPortalClient()
        run(client)
        val attrs = client.started("TaggedSeveritySpec").rq.attributes
        attrs.any { it.key == "tag" && it.value == "Smoke" } shouldBe true
    }
}) {
    @Tags("Smoke")
    class TaggedSeveritySpec : FunSpec({
        test("tagged test")
            .config(
                tags = setOf(NamedTag("team:payments"), NamedTag("fast")),
                severity = TestCaseSeverityLevel.CRITICAL,
            ) { 1 shouldBe 1 }
    })
}
