package io.github.qasecret.rp

import io.github.qasecret.rp.support.RecordingReportPortalClient
import io.github.qasecret.rp.support.RecordingReportPortalClient.Companion.reportPortal
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.TestEngineLauncher
import io.kotest.engine.concurrency.TestExecutionMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Proves logs are attributed to the correct item even under CONCURRENT tests that hop threads — the
 * case where a plain thread-local breaks (every test's logs collapse onto the last one). The MDC item
 * key propagated via the test coroutine ([io.github.qasecret.rp.internal.RpItemContextElement]) keeps
 * each test's logs on its own item.
 */
class ConcurrentLoggingTest : FunSpec({

    test("logs are attributed correctly under concurrency and coroutine thread-hops") {
        val client = RecordingReportPortalClient()
        val extension = ReportPortalExtension(reportPortal(client), RpConfig())
        val projectConfig = object : AbstractProjectConfig() {
            override val extensions: List<Extension> = listOf(extension)
            // Kotest 6 replaced the spec-level `concurrency`/`dispatcherAffinity` toggles with a
            // project-level execution mode. Concurrent runs the spec's tests on a shared dispatcher
            // pool so they interleave (and hop threads), which is exactly what this test exercises.
            override val testExecutionMode: TestExecutionMode = TestExecutionMode.Concurrent
        }

        TestEngineLauncher().withProjectConfig(projectConfig).withClasses(ConcurrentSpec::class).launch()

        for (n in 1..6) {
            val item = client.started("conc test $n")
            client.logsFor(item.uuid).map { it.message } shouldBe listOf("CONC-$n", "CONC-$n-after-hop")
        }
    }
}) {
    class ConcurrentSpec : FunSpec({
        for (n in 1..6) {
            test("conc test $n") {
                delay(30) // force the concurrent tests to interleave
                ReportPortalLogs.info("CONC-$n")
                withContext(Dispatchers.IO) {
                    ReportPortalLogs.info("CONC-$n-after-hop") // logs from a hopped thread
                }
            }
        }
    })
}
