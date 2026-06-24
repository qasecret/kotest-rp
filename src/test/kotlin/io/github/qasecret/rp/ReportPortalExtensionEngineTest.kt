package io.github.qasecret.rp

import io.github.qasecret.rp.support.RecordingReportPortalClient
import io.github.qasecret.rp.support.RecordingReportPortalClient.Companion.reportPortal
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.TestEngineLauncher
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.reflect.KClass

/**
 * Drives sample specs through the real Kotest engine with the extension wired to a recording
 * ReportPortal client, then asserts the recorded launch/item tree. This proves the lifecycle fixes
 * (spec finished, container/leaf types, ignored tests, parent links, failure reporting) end-to-end.
 */
class ReportPortalExtensionEngineTest : FunSpec({

    fun run(client: RecordingReportPortalClient, config: RpConfig, vararg specs: KClass<out io.kotest.core.spec.Spec>) {
        val extension = ReportPortalExtension(reportPortal(client), config)
        val projectConfig = object : AbstractProjectConfig() {
            override val extensions: List<Extension> = listOf(extension)
        }
        TestEngineLauncher().withProjectConfig(projectConfig).withClasses(*specs).launch()
    }

    test("launch is started and finished, spec is a SUITE, leaf is a STEP under the spec") {
        val client = RecordingReportPortalClient()
        run(client, RpConfig(), PassingSpec::class)

        client.launches shouldHaveSize 1
        client.finishedLaunches shouldBe listOf("launch-uuid")

        val spec = client.started("PassingSpec")
        spec.rq.type shouldBe "SUITE"
        spec.parentUuid shouldBe null // no synthetic root -> spec attaches to launch
        spec.rq.codeRef shouldBe PassingSpec::class.java.name

        val leaf = client.started("passes")
        leaf.rq.type shouldBe "STEP"
        leaf.parentUuid shouldBe spec.uuid

        client.finishOf(leaf.uuid).rq.status shouldBe "PASSED"
        client.finishOf(spec.uuid).rq.status shouldBe "PASSED"
    }

    test("nested containers are SUITE items with correct parent links") {
        val client = RecordingReportPortalClient()
        run(client, RpConfig(), NestedSpec::class)

        val spec = client.started("NestedSpec")
        // DescribeSpec applies a "Describe: " affix, which the mapper preserves via TestName.
        val group = client.started("Describe: a group")
        group.rq.type shouldBe "SUITE"
        group.parentUuid shouldBe spec.uuid

        val leaf = client.started("works")
        leaf.rq.type shouldBe "STEP"
        leaf.parentUuid shouldBe group.uuid
    }

    test("failed test is FAILED with an issue and an error log bound to the item") {
        val client = RecordingReportPortalClient()
        run(client, RpConfig(), FailingSpec::class)

        val leaf = client.started("boom")
        val finish = client.finishOf(leaf.uuid).rq
        finish.status shouldBe "FAILED"
        finish.issue.shouldNotBeNull()
        finish.issue.issueType shouldBe "ti"

        val logs = client.logsFor(leaf.uuid)
        logs shouldHaveSize 1
        logs.first().level shouldBe "ERROR"
        logs.first().message shouldContain "nope"
    }

    test("ignored test is reported as SKIPPED even though beforeTest never fires") {
        val client = RecordingReportPortalClient()
        run(client, RpConfig(), IgnoredSpec::class)

        val skipped = client.startedOrNull("skipped")
        skipped.shouldNotBeNull()
        client.finishOf(skipped.uuid).rq.status shouldBe "SKIPPED"
    }

    test("spec is started and finished exactly once under InstancePerLeaf") {
        val client = RecordingReportPortalClient()
        run(client, RpConfig(), InstancePerLeafSpec::class)

        val specStarts = client.startedItems.filter { it.rq.name == "InstancePerLeafSpec" }
        specStarts shouldHaveSize 1
        client.finishedItems.filter { it.uuid == specStarts.first().uuid } shouldHaveSize 1
    }
}) {
    class PassingSpec : FunSpec({
        test("passes") { 1 shouldBe 1 }
    })

    class NestedSpec : DescribeSpec({
        describe("a group") {
            it("works") { 1 shouldBe 1 }
        }
    })

    class FailingSpec : FunSpec({
        test("boom") { throw AssertionError("nope") }
    })

    class IgnoredSpec : FunSpec({
        // A real (enabled) test keeps the spec active. Kotest 6 delivers the disabled `xtest` via the
        // dedicated IgnoredTestListener (not finalizeSpec/afterTest); a spec containing ONLY disabled
        // tests would instead be skipped wholesale at the spec level, which is a different path.
        test("runs") { 1 shouldBe 1 }
        xtest("skipped") { 1 shouldBe 1 }
    })

    class InstancePerLeafSpec : FunSpec() {
        override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

        init {
            test("first") { 1 shouldBe 1 }
            test("second") { 2 shouldBe 2 }
        }
    }
}
