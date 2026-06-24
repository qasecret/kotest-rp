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
 * Regression coverage for `withData` (datatest). Kotest 6 types every `withData` data node as
 * [io.kotest.core.test.TestType.Container] — even one whose block only asserts (no nested tests),
 * which is really a leaf. `RpReporter` defers each container's RP item: it becomes a SUITE the moment a
 * child appears, or a leaf STEP at finish if none ever does. Reporting a childless data node eagerly as
 * a SUITE would leave an empty suite contributing no pass/fail counts; reporting one with children as a
 * leaf STEP would make RP treat the real tests as nested steps that never roll up into statistics.
 */
class WithDataReportingTest : FunSpec({

    fun run(client: RecordingReportPortalClient, vararg specs: KClass<out io.kotest.core.spec.Spec>) {
        val extension = ReportPortalExtension(reportPortal(client), RpConfig())
        val projectConfig = object : AbstractProjectConfig() {
            override val extensions: List<Extension> = listOf(extension)
        }
        TestEngineLauncher().withProjectConfig(projectConfig).withClasses(*specs).launch()
    }

    test("a withData node that opens nested containers is a SUITE, with the children nested under it") {
        val client = RecordingReportPortalClient()
        run(client, NestedDataSpec::class)

        // In Kotest 6 a `withData` row opened inside a BehaviorSpec `Context` is itself a Context, so
        // its name carries the `Context: ` affix.
        val scenario = client.started("Context: Scenario 1")
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

    test("logs emitted in a withData leaf body are buffered and attach to the materialized leaf") {
        val client = RecordingReportPortalClient()
        run(client, LoggingLeafDataSpec::class)

        // The leaf's RP item does not exist while its body runs (the container is deferred), so the log
        // is buffered and flushed onto the item the moment it is materialized — not dropped.
        val leaf = client.started("datum-9")
        client.logsFor(leaf.uuid).map { it.message } shouldBe listOf("from-leaf-body")
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
                    io.kotest.core.names.TestNameBuilder.builder("it is verified").build(),
                    io.kotest.core.spec.style.TestXMethod.DISABLED,
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

    class LoggingLeafDataSpec : FunSpec({
        context("numbers") {
            withData(
                nameFn = { "datum-$it" },
                ts = listOf(9),
            ) { n ->
                ReportPortalLogs.info("from-leaf-body")
                n shouldBe 9
            }
        }
    })
}
