package io.github.qasecret.rp

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.TestEngineLauncher
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live demo of multi-invocation (flaky-detection) reporting: a stable test that passes all 3
 * invocations, and a flaky test that fails on the 2nd of 3. Disabled by default; enable with
 * `-Drp.live=true`.
 *
 * `./gradlew test --tests "io.github.qasecret.rp.LiveFlakyReportPortalTest" -Drp.live=true`
 */
class LiveFlakyReportPortalTest : FunSpec({

    val live = System.getProperty("rp.live") == "true"

    test("reports invocation markers for repeated tests").config(enabled = live) {
        val extension = ReportPortalExtension()
        val projectConfig = object : AbstractProjectConfig() {
            override val extensions: List<Extension> = listOf(extension)
        }

        TestEngineLauncher()
            .withProjectConfig(projectConfig)
            .withClasses(RepeatedSpec::class)
            .launch()

        println("[live] invocation markers reported to ReportPortal")
    }
}) {
    class RepeatedSpec : FunSpec({
        test("stable under repetition").config(invocations = 3) {
            check(true)
        }
        test("flaky: fails on the 2nd run").config(invocations = 3) {
            val run = flakyCounter.incrementAndGet()
            if (run == 2) throw AssertionError("flaked on invocation $run")
        }
    }) {
        companion object {
            val flakyCounter = AtomicInteger()
        }
    }
}
