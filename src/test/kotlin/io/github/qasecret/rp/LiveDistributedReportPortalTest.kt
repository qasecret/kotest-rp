package io.github.qasecret.rp

import com.epam.reportportal.listeners.ListenerParameters
import com.epam.reportportal.service.ReportPortal
import com.epam.reportportal.utils.properties.PropertiesLoader
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.TestEngineLauncher
import io.kotest.matchers.shouldBe

/**
 * Live demo of distributed reporting: attaches to an externally-created launch (UUID supplied via
 * `-Drp.shard.launchUuid`) so multiple "shards" contribute to one launch. Driven by a script that
 * creates the launch, runs this test once per shard, then finishes the launch. Disabled by default.
 *
 * `./gradlew test --tests "io.github.qasecret.rp.LiveDistributedReportPortalTest" \
 *      -Drp.live=true -Drp.shard.launchUuid=<uuid> -Drp.shard.id=A`
 */
class LiveDistributedReportPortalTest : FunSpec({

    val live = System.getProperty("rp.live") == "true"
    val launchUuid = System.getProperty("rp.shard.launchUuid")
    val shard = System.getProperty("rp.shard.id") ?: "A"

    test("attaches this shard to the shared launch").config(enabled = live && launchUuid != null) {
        // Start from the on-classpath reportportal.properties, then override the launch UUID so the
        // extension attaches to the orchestrator's launch instead of creating its own.
        val params = ListenerParameters(PropertiesLoader.load()).apply {
            this.launchUuid = launchUuid
        }
        val reportPortal = ReportPortal.builder().withParameters(params).build()
        val extension = ReportPortalExtension(reportPortal, RpConfig())
        val projectConfig = object : AbstractProjectConfig() {
            override val extensions: List<Extension> = listOf(extension)
        }

        val spec = if (shard == "B") ShardBSpec::class else ShardASpec::class
        TestEngineLauncher().withProjectConfig(projectConfig).withClasses(spec).launch()

        println("[live] shard $shard attached to launch $launchUuid")
    }
}) {
    class ShardASpec : FunSpec({
        test("shard A test one") { 1 shouldBe 1 }
        test("shard A test two") { 1 shouldBe 1 }
    })

    class ShardBSpec : FunSpec({
        test("shard B test one") { 1 shouldBe 1 }
        test("shard B test two") { 1 shouldBe 1 }
    })
}
