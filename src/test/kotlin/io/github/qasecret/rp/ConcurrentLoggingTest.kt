package io.github.qasecret.rp

import io.github.qasecret.rp.support.RecordingReportPortalClient
import io.github.qasecret.rp.support.RecordingReportPortalClient.Companion.reportPortal
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.TestEngineLauncher
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
            override fun extensions(): List<Extension> = listOf(extension)
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
    }) {
        @OptIn(io.kotest.common.ExperimentalKotest::class)
        override fun concurrency(): Int = 6

        @OptIn(io.kotest.common.ExperimentalKotest::class)
        override fun dispatcherAffinity(): Boolean = false
    }
}
