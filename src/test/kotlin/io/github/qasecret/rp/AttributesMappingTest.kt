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

    fun run(client: RecordingReportPortalClient, vararg specs: kotlin.reflect.KClass<out io.kotest.core.spec.Spec>) {
        val extension = ReportPortalExtension(reportPortal(client), RpConfig())
        val projectConfig = object : AbstractProjectConfig() {
            override val extensions: List<Extension> = listOf(extension)
        }
        val targets = if (specs.isEmpty()) arrayOf(TaggedSeveritySpec::class) else specs
        TestEngineLauncher().withProjectConfig(projectConfig).withClasses(*targets).launch()
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

    test("a test inherits tags from an enclosing container") {
        val client = RecordingReportPortalClient()
        run(client, InheritedConfigSpec::class)
        // Kotest 6's TestConfig is a sparse per-test override; tags set on the container live on its
        // TestCase, so the leaf must pick them up by walking its parent chain rather than reading only
        // its own (empty) override.
        val attrs = client.started("inherits").rq.attributes
        attrs.any { it.key == null && it.value == "slow" } shouldBe true
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

    class InheritedConfigSpec : FunSpec({
        context("outer")
            .config(tags = setOf(NamedTag("slow"))) {
                test("inherits") { 1 shouldBe 1 }
            }
    })
}
