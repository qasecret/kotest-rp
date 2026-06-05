package io.github.qasecret.rp

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.core.spec.style.FreeSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.WordSpec
import io.kotest.engine.TestEngineLauncher
import io.kotest.matchers.shouldBe

/**
 * Live smoke test exercising ALL Kotest spec styles against a real ReportPortal instance, so we can
 * see how each style's structure (containers, leaves, affixes) maps to the RP SUITE/STEP tree.
 *
 * Disabled by default; enable with `-Drp.live=true` and a `reportportal.properties` on the classpath:
 * `./gradlew test --tests "io.github.qasecret.rp.LiveStylesReportPortalTest" -Drp.live=true`
 */
class LiveStylesReportPortalTest : FunSpec({

    val live = System.getProperty("rp.live") == "true"

    test("reports every Kotest spec style to ReportPortal").config(enabled = live) {
        val extension = ReportPortalExtension()
        val projectConfig = object : AbstractProjectConfig() {
            override fun extensions(): List<Extension> = listOf(extension)
        }

        TestEngineLauncher()
            .withProjectConfig(projectConfig)
            .withClasses(
                FunSpecSample::class,
                StringSpecSample::class,
                ShouldSpecSample::class,
                DescribeSpecSample::class,
                BehaviorSpecSample::class,
                WordSpecSample::class,
                FeatureSpecSample::class,
                ExpectSpecSample::class,
                FreeSpecSample::class,
                AnnotationSpecSample::class,
            )
            .launch()

        println("[live] all spec styles reported to ReportPortal")
    }
}) {
    class FunSpecSample : FunSpec({
        test("root level test") { 1 shouldBe 1 }
        context("a context") {
            test("nested test") { 1 shouldBe 1 }
            test("nested failing test") { 1 shouldBe 2 }
        }
    })

    class StringSpecSample : StringSpec({
        "addition works" { (1 + 1) shouldBe 2 }
        "subtraction works" { (2 - 1) shouldBe 1 }
    })

    class ShouldSpecSample : ShouldSpec({
        should("work at the root") { 1 shouldBe 1 }
        context("when grouped") {
            should("work when nested") { 1 shouldBe 1 }
        }
    })

    class DescribeSpecSample : DescribeSpec({
        describe("a calculator") {
            it("adds") { (1 + 1) shouldBe 2 }
            context("when subtracting") {
                it("subtracts") { (2 - 1) shouldBe 1 }
            }
        }
    })

    class BehaviorSpecSample : BehaviorSpec({
        given("a logged in user") {
            `when`("they open the dashboard") {
                then("widgets are shown") { 1 shouldBe 1 }
            }
        }
    })

    class WordSpecSample : WordSpec({
        "a stack" should {
            "increase size on push" { 1 shouldBe 1 }
            "decrease size on pop" { 1 shouldBe 1 }
        }
    })

    class FeatureSpecSample : FeatureSpec({
        feature("login") {
            scenario("valid credentials succeed") { 1 shouldBe 1 }
            scenario("invalid credentials fail") { 1 shouldBe 1 }
        }
    })

    class ExpectSpecSample : ExpectSpec({
        context("a parser") {
            expect("parses integers") { 1 shouldBe 1 }
        }
    })

    class FreeSpecSample : FreeSpec({
        "a group" - {
            "does something" { 1 shouldBe 1 }
            "does something else" { 1 shouldBe 1 }
        }
    })

    class AnnotationSpecSample : AnnotationSpec() {
        @Test
        fun `first annotated test`() { 1 shouldBe 1 }

        @Test
        fun `second annotated test`() { 2 shouldBe 2 }
    }
}
