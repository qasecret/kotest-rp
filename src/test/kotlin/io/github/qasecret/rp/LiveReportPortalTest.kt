package io.github.qasecret.rp

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.TestEngineLauncher
import io.kotest.matchers.shouldBe

/**
 * Smoke test against a LIVE ReportPortal instance. Disabled by default; enable with `-Drp.live=true`
 * (forwarded to the test JVM by build.gradle.kts) and a `reportportal.properties` on the test
 * classpath. It drives a small set of sample specs through the real [ReportPortalExtension] so a real
 * launch is created, then you can inspect it in the ReportPortal UI / REST API.
 *
 * Run: `./gradlew test --tests "io.github.qasecret.rp.LiveReportPortalTest" -Drp.live=true`
 */
class LiveReportPortalTest : FunSpec({

    val live = System.getProperty("rp.live") == "true"

    test("reports a real launch to the configured ReportPortal").config(enabled = live) {
        val extension = ReportPortalExtension() // reads reportportal.properties from the classpath
        val projectConfig = object : AbstractProjectConfig() {
            override fun extensions(): List<Extension> = listOf(extension)
        }

        TestEngineLauncher()
            .withProjectConfig(projectConfig)
            .withClasses(
                LivePassingSpec::class,
                LiveFailingSpec::class,
                LiveErroringSpec::class,
                LiveSkippedSpec::class,
                LiveNestedSpec::class,
            )
            .launch()

        println("[live] launch reported to ReportPortal — inspect it in the UI / via the REST API")
    }
}) {
    class LivePassingSpec : FunSpec({
        test("passes") { 1 shouldBe 1 }
    })

    class LiveFailingSpec : FunSpec({
        test("fails with assertion") { 2 shouldBe 3 }
    })

    class LiveErroringSpec : FunSpec({
        test("fails with exception") { throw RuntimeException("kaboom from kotest-rp live test") }
    })

    class LiveSkippedSpec : FunSpec({
        xtest("is skipped") { 1 shouldBe 1 }
    })

    class LiveNestedSpec : DescribeSpec({
        describe("a feature") {
            it("works") { 1 shouldBe 1 }
            it("also works") { 2 shouldBe 2 }
        }
    })
}
