package io.github.qasecret.rp

import io.github.qasecret.rp.support.RecordingReportPortalClient
import io.github.qasecret.rp.support.RecordingReportPortalClient.Companion.reportPortal
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.TestEngineLauncher
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.reflect.KClass

/** Verifies that [RpConfig] toggles change the reported structure as documented. */
class ReportPortalExtensionConfigTest : FunSpec({

    fun run(client: RecordingReportPortalClient, config: RpConfig, vararg specs: KClass<out io.kotest.core.spec.Spec>) {
        val extension = ReportPortalExtension(reportPortal(client), config)
        val projectConfig = object : AbstractProjectConfig() {
            override fun extensions(): List<Extension> = listOf(extension)
        }
        TestEngineLauncher().withProjectConfig(projectConfig).withClasses(*specs).launch()
    }

    test("syntheticRootSuite nests specs under a named root SUITE") {
        val client = RecordingReportPortalClient()
        run(client, RpConfig(syntheticRootSuite = true, rootSuiteName = "My Root"), SampleSpec::class)

        val root = client.started("My Root")
        root.rq.type shouldBe "SUITE"
        root.parentUuid shouldBe null

        client.started("SampleSpec").parentUuid shouldBe root.uuid
    }

    test("leafItemType = TEST reports leaves as TEST") {
        val client = RecordingReportPortalClient()
        run(client, RpConfig(leafItemType = LeafItemType.TEST), SampleSpec::class)

        client.started("a test").rq.type shouldBe "TEST"
    }

    test("reportIgnored = false suppresses SKIPPED items") {
        val client = RecordingReportPortalClient()
        run(client, RpConfig(reportIgnored = false), SkippingSpec::class)

        client.startedOrNull("ignored") shouldBe null
    }

    test("failureDefectType = null omits the issue on failures") {
        val client = RecordingReportPortalClient()
        run(client, RpConfig(failureDefectType = null), BreakingSpec::class)

        val leaf = client.started("breaks")
        val finish = client.finishOf(leaf.uuid).rq
        finish.status shouldBe "FAILED"
        finish.issue.shouldBeNull()
    }
}) {
    class SampleSpec : FunSpec({
        test("a test") { 1 shouldBe 1 }
    })

    class SkippingSpec : FunSpec({
        xtest("ignored") { 1 shouldBe 1 }
    })

    class BreakingSpec : FunSpec({
        test("breaks") { throw AssertionError("kaboom") }
    })
}
