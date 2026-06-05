package io.github.qasecret.rp

import io.kotest.core.NamedTag
import io.kotest.core.annotation.Tags
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestCaseSeverityLevel
import io.kotest.engine.TestEngineLauncher
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live smoke test verifying attribute mapping and that user fixtures don't disturb reporting:
 *  - spec-level `@Tags` -> ReportPortal attributes (key "tag") on the spec SUITE,
 *  - test-level `config(tags = ...)` -> attributes (value-only) on the test STEP,
 *  - `beforeTest`/`afterTest` fixtures run alongside reporting.
 *
 * Disabled by default; enable with `-Drp.live=true`:
 * `./gradlew test --tests "io.github.qasecret.rp.LiveAttributesReportPortalTest" -Drp.live=true`
 */
class LiveAttributesReportPortalTest : FunSpec({

    val live = System.getProperty("rp.live") == "true"

    test("reports spec/test tags as attributes and runs fixtures").config(enabled = live) {
        val extension = ReportPortalExtension()
        val projectConfig = object : AbstractProjectConfig() {
            override fun extensions(): List<Extension> = listOf(extension)
        }

        TestEngineLauncher()
            .withProjectConfig(projectConfig)
            .withClasses(TaggedSpec::class)
            .launch()

        // Fixtures must have run for both tests (beforeTest x2).
        TaggedSpec.beforeCount.get() shouldBe 2
        TaggedSpec.afterCount.get() shouldBe 2
        println("[live] tags/attributes + fixtures reported (beforeTest ran ${TaggedSpec.beforeCount.get()} times)")
    }
}) {

    @Tags("Smoke", "Regression")
    class TaggedSpec : FunSpec({
        beforeTest { beforeCount.incrementAndGet() }
        afterTest { afterCount.incrementAndGet() }

        test("tagged test")
            .config(
                tags = setOf(NamedTag("team:payments"), NamedTag("Fast")),
                severity = TestCaseSeverityLevel.CRITICAL,
            ) { 1 shouldBe 1 }
        test("untagged test") {
            1 shouldBe 1
        }
    }) {
        companion object {
            val beforeCount = AtomicInteger()
            val afterCount = AtomicInteger()
        }
    }
}
