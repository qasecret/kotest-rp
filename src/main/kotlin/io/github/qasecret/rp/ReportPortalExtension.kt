package io.github.qasecret.rp

import com.epam.reportportal.service.ReportPortal
import io.github.qasecret.rp.internal.RpItemContextElement
import io.github.qasecret.rp.internal.RpMapper
import io.github.qasecret.rp.internal.RpReporter
import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.listeners.AfterTestListener
import io.kotest.core.listeners.BeforeInvocationListener
import io.kotest.core.listeners.BeforeTestListener
import io.kotest.core.listeners.FinalizeSpecListener
import io.kotest.core.listeners.PrepareSpecListener
import io.kotest.core.listeners.ProjectListener
import io.kotest.core.spec.Spec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * Kotest extension that reports test execution to [ReportPortal](https://reportportal.io).
 *
 * Register it from your project config:
 * ```
 * class ProjectConfig : AbstractProjectConfig() {
 *     override fun extensions() = listOf(ReportPortalExtension())
 * }
 * ```
 *
 * The no-arg constructor reads the standard ReportPortal configuration (classpath
 * `reportportal.properties` / `rp.*` system properties and environment variables). Use [RpConfig] to
 * customize reporting behavior, or inject a pre-built [ReportPortal] for advanced/testing scenarios.
 *
 * Reporting is non-fatal: any ReportPortal failure is logged and swallowed so it never affects the
 * outcome of your tests.
 */
class ReportPortalExtension internal constructor(
    private val reporter: RpReporter,
) : ProjectListener, PrepareSpecListener, BeforeTestListener, AfterTestListener, FinalizeSpecListener,
    BeforeInvocationListener, TestCaseExtension {

    /** Reports to ReportPortal using configuration resolved from properties/environment. */
    constructor() : this(RpConfig())

    /** Reports to ReportPortal using the supplied [config]. */
    constructor(config: RpConfig) : this(RpReporter(buildReportPortal(config), config))

    /** Reports to the supplied [reportPortal] instance, applying [config] for reporting behavior. */
    constructor(reportPortal: ReportPortal, config: RpConfig = RpConfig()) :
        this(RpReporter(reportPortal, config))

    @Suppress("OVERRIDE_DEPRECATION")
    override val name: String = "ReportPortalExtension"

    override suspend fun beforeProject() = guard { reporter.startLaunch() }

    override suspend fun prepareSpec(kclass: KClass<out Spec>) = guard { reporter.startSpec(kclass) }

    override suspend fun beforeTest(testCase: TestCase) = guard { reporter.startTest(testCase) }

    override suspend fun beforeInvocation(testCase: TestCase, invocation: Int) =
        guard { reporter.logInvocation(testCase, invocation) }

    override suspend fun afterTest(testCase: TestCase, result: TestResult) =
        guard { reporter.finishTest(testCase, result) }

    override suspend fun finalizeSpec(kclass: KClass<out Spec>, results: Map<TestCase, TestResult>) =
        guard { reporter.finishSpec(kclass, results) }

    override suspend fun afterProject() = guard { reporter.finishLaunch() }

    /**
     * Pins this test's ReportPortal item key into the test coroutine (via SLF4J MDC) for the duration
     * of its execution, so logback/SLF4J logs and [ReportPortalLogs] calls attach to the correct item
     * even across coroutine thread-hops and under concurrent execution. Purely additive: it only wraps
     * the execution context and never alters the test result. Non-fatal like every other hook — if the
     * item key can't be resolved, the test runs unwrapped rather than failing.
     */
    override suspend fun intercept(
        testCase: TestCase,
        execute: suspend (TestCase) -> TestResult,
    ): TestResult {
        val key = try {
            RpMapper.testKey(testCase)
        } catch (e: Throwable) {
            logger.debug("Failed to resolve ReportPortal item key; logging context not set", e)
            null
        }
        return if (key == null) execute(testCase)
        else withContext(RpItemContextElement(key)) { execute(testCase) }
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            logger.debug("ReportPortal extension callback failed", e)
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(ReportPortalExtension::class.java)

        private fun buildReportPortal(config: RpConfig): ReportPortal {
            val builder = ReportPortal.builder()
            config.parameters?.let { builder.withParameters(it) }
            return builder.build()
        }
    }
}
