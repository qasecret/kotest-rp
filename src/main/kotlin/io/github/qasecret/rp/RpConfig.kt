package io.github.qasecret.rp

import com.epam.reportportal.listeners.ListenerParameters
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult

/**
 * ReportPortal defect-type locators for the built-in defect groups. Pass any of these (or a custom
 * project-specific locator string) as a defect type. See `RpConfig.failureDefectType` /
 * `RpConfig.defectTypeResolver`.
 */
object RpDefect {
    const val TO_INVESTIGATE = "ti"
    const val PRODUCT_BUG = "pb"
    const val AUTOMATION_BUG = "ab"
    const val SYSTEM_ISSUE = "si"
    const val NO_DEFECT = "nd"
}

/**
 * ReportPortal item type used for leaf (non-container) Kotest tests.
 *
 * [STEP] is the ReportPortal-idiomatic leaf type and renders best in the timeline; [TEST] produces a
 * flatter structure that some older dashboards key off.
 */
enum class LeafItemType(internal val rpType: String) {
    STEP("STEP"),
    TEST("TEST"),
}

/**
 * Configuration for [ReportPortalExtension].
 *
 * All values have sensible defaults, so `ReportPortalExtension()` works out of the box and reads the
 * standard ReportPortal configuration (classpath `reportportal.properties` / `rp.*` system properties
 * and environment variables). Override individual fields to change reporting behavior.
 */
data class RpConfig(
    /**
     * When `true`, all specs are nested under a single synthetic root SUITE named [rootSuiteName]
     * (`Launch -> "Test Suite" -> Spec -> tests`). When `false` (default), specs attach directly to
     * the launch (`Launch -> Spec -> tests`), which is the idiomatic ReportPortal structure.
     */
    val syntheticRootSuite: Boolean = false,
    /** Name of the synthetic root suite; only used when [syntheticRootSuite] is `true`. */
    val rootSuiteName: String = "Test Suite",
    /** ReportPortal item type used for leaf tests. */
    val leafItemType: LeafItemType = LeafItemType.STEP,
    /** When `true` (default), Kotest's ignored/disabled tests are reported as SKIPPED items. */
    val reportIgnored: Boolean = true,
    /**
     * Default defect type locator applied to failed tests (see [RpDefect]). `null` disables defect
     * typing (failures are still marked FAILED). Ignored when [defectTypeResolver] is set.
     */
    val failureDefectType: String? = RpDefect.TO_INVESTIGATE,
    /**
     * Optional per-failure defect resolver. When set, it fully controls the defect type for each
     * failed test (returning `null` = no defect), overriding [failureDefectType]. Use it to map, for
     * example, assertion failures to a product bug and exceptions to an automation bug:
     * ```
     * defectTypeResolver = { _, result ->
     *     if (result is TestResult.Failure) RpDefect.PRODUCT_BUG else RpDefect.AUTOMATION_BUG
     * }
     * ```
     */
    val defectTypeResolver: ((TestCase, TestResult) -> String?)? = null,
    /**
     * Optional programmatic ReportPortal connection parameters. When `null` (default), parameters are
     * resolved from `reportportal.properties` / `rp.*` system properties and environment variables.
     */
    val parameters: ListenerParameters? = null,
)
