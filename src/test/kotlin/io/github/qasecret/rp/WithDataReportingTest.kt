package io.github.qasecret.rp

import io.github.qasecret.rp.support.RecordingReportPortalClient
import io.github.qasecret.rp.support.RecordingReportPortalClient.Companion.reportPortal
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.TestEngineLauncher
import io.kotest.matchers.shouldBe
import kotlin.reflect.KClass

/**
 * Regression coverage for `withData` (datatest). Kotest types a `withData` data node as
 * [io.kotest.core.test.TestType.Dynamic], which is a leaf ONLY when the data block makes no nested
 * tests. When the block opens nested containers (the common `Context -> withData -> Given/When/Then`
 * shape) the data node is really a container, and reporting it to ReportPortal as a leaf STEP makes RP
 * treat all the real tests as nested steps of one item — so they never roll up into launch statistics.
 */
class WithDataReportingTest : FunSpec({

    fun run(client: RecordingReportPortalClient, vararg specs: KClass<out io.kotest.core.spec.Spec>) {
        val extension = ReportPortalExtension(reportPortal(client), RpConfig())
        val projectConfig = object : AbstractProjectConfig() {
            override fun extensions(): List<Extension> = listOf(extension)
        }
        TestEngineLauncher().withProjectConfig(projectConfig).withClasses(*specs).launch()
    }

    test("a withData node that opens nested containers is a SUITE, with the children nested under it") {
        val client = RecordingReportPortalClient()
        run(client, NestedDataSpec::class)

        val scenario = client.started("Scenario 1")
        scenario.rq.type shouldBe "SUITE"

        val given = client.started("Given: an authorized user")
        given.rq.type shouldBe "SUITE"
        given.parentUuid shouldBe scenario.uuid

        // The actual test leaf rolls up under the scenario SUITE.
        val then = client.started("Then: it is verified")
        then.rq.type shouldBe "STEP"
        client.finishOf(then.uuid).rq.status shouldBe "PASSED"

        // Universal invariant: nothing may be nested under a leaf (STEP/TEST). RP treats children of a
        // leaf as nested steps, which is exactly what stops tests rolling up into launch statistics.
        val typeByUuid = client.startedItems.associate { it.uuid to it.rq.type }
        val leafParents = client.startedItems
            .mapNotNull { it.parentUuid }
            .filter { typeByUuid[it] !in setOf("SUITE", null) }
        leafParents shouldBe emptyList()
    }

    test("a withData node whose children are all ignored is still a SUITE, with skipped tests under it") {
        val client = RecordingReportPortalClient()
        run(client, AllIgnoredDataSpec::class)

        val scenario = client.started("Scenario 1")
        scenario.rq.type shouldBe "SUITE"

        // The ignored child must roll up as an independent SKIPPED item under the scenario SUITE, not as
        // a nested step of a STEP-typed scenario.
        val skipped = client.started("it is verified")
        skipped.parentUuid shouldBe scenario.uuid
        client.finishOf(skipped.uuid).rq.status shouldBe "SKIPPED"

        // Same universal invariant: nothing parented under a STEP/TEST leaf.
        val typeByUuid = client.startedItems.associate { it.uuid to it.rq.type }
        val leafParents = client.startedItems
            .mapNotNull { it.parentUuid }
            .filter { typeByUuid[it] !in setOf("SUITE", null) }
        leafParents shouldBe emptyList()
    }

    test("a withData leaf (no nested tests) stays a leaf STEP and finishes with its status") {
        val client = RecordingReportPortalClient()
        run(client, LeafDataSpec::class)

        val leaf = client.started("datum-7")
        leaf.rq.type shouldBe "STEP"
        client.finishOf(leaf.uuid).rq.status shouldBe "PASSED"
    }
}) {

    class NestedDataSpec : BehaviorSpec({
        Context("data driven") {
            withData(
                nameFn = { "Scenario $it" },
                ts = listOf(1),
            ) {
                Given("an authorized user") {
                    When("the user acts") {
                        Then("it is verified") { 1 shouldBe 1 }
                    }
                }
            }
        }
    })

    class AllIgnoredDataSpec : FunSpec({
        context("data driven") {
            withData(
                nameFn = { "Scenario $it" },
                ts = listOf(1),
            ) {
                // The data node's only child is a disabled leaf, so no beforeTest fires beneath the data
                // node and nothing materializes it during the run. It is reported only as a SKIPPED item
                // from finishSpec — which must still nest under a SUITE, not under a leaf STEP. Registered
                // via the low-level ContainerScope API because the FunSpec `xtest` DSL isn't visible on
                // the generic `withData` receiver.
                registerTest(
                    io.kotest.core.names.TestName("it is verified"),
                    disabled = true,
                    null,
                    io.kotest.core.test.TestType.Test,
                ) { 1 shouldBe 1 }
            }
        }
    })

    class LeafDataSpec : FunSpec({
        context("numbers") {
            withData(
                nameFn = { "datum-$it" },
                ts = listOf(7),
            ) { n ->
                n shouldBe 7
            }
        }
    })
}
