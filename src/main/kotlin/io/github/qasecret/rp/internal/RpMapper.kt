package io.github.qasecret.rp.internal

import com.epam.reportportal.listeners.ItemStatus
import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ
import io.github.qasecret.rp.LeafItemType
import io.github.qasecret.rp.RpConfig
import io.kotest.core.annotation.Tags
import io.kotest.core.descriptors.Descriptor
import io.kotest.core.spec.Spec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.core.test.TestType
import kotlin.reflect.KClass

/**
 * Pure, side-effect-free translation between Kotest model objects and ReportPortal values.
 *
 * Everything here is deterministic and depends only on its arguments, which keeps it trivially
 * unit-testable and free of any ReportPortal client or engine state.
 */
internal object RpMapper {

    private const val SPEC_TAG_KEY = "tag"

    // --- Item-tree keys (stable, hashCode-free; survive InstancePerLeaf/InstancePerTest) ---

    fun specKey(kclass: KClass<out Spec>): String = "spec:${kclass.java.name}"

    fun specKey(spec: Spec): String = specKey(spec::class)

    fun testKey(testCase: TestCase): String = testKey(testCase.descriptor)

    fun testKey(descriptor: Descriptor.TestDescriptor): String = "test:${descriptor.path(true).value}"

    /**
     * Key of the parent item this test should be nested under: the parent test when nested in a
     * container, the spec when the test is at the root of the spec, or `null` when undetermined
     * (the reporter then falls back to the synthetic root suite / launch).
     */
    fun parentKey(testCase: TestCase): String? = when (val parent = testCase.descriptor.parent) {
        is Descriptor.TestDescriptor -> testKey(parent)
        is Descriptor.SpecDescriptor -> specKey(testCase.spec)
        else -> null
    }

    // --- Names ---

    /**
     * Human-readable item name, composed from the Kotest [io.kotest.core.names.TestName] affixes
     * (e.g. BehaviorSpec's `Given `/`When `/`Then ` prefixes) without the fragile string surgery the
     * previous implementation used.
     */
    fun displayName(testCase: TestCase): String = with(testCase.name) {
        buildString {
            prefix?.takeIf { it.isNotBlank() }?.let { append(it) }
            append(testName)
            suffix?.takeIf { it.isNotBlank() }?.let { append(it) }
        }
    }

    fun specName(kclass: KClass<out Spec>): String = kclass.simpleName ?: kclass.java.name

    // --- ReportPortal item types ---

    fun itemType(testCase: TestCase, config: RpConfig): String = when (testCase.type) {
        TestType.Container -> "SUITE"
        TestType.Test, TestType.Dynamic -> config.leafItemType.rpType
    }

    fun leafType(config: RpConfig): String = config.leafItemType.rpType

    // --- Status ---

    fun status(result: TestResult): ItemStatus = when (result) {
        is TestResult.Success -> ItemStatus.PASSED
        is TestResult.Failure, is TestResult.Error -> ItemStatus.FAILED
        is TestResult.Ignored -> ItemStatus.SKIPPED
    }

    // --- Code references / history (stable across runs, independent of display formatting) ---

    fun codeRef(testCase: TestCase): String =
        "${testCase.spec::class.java.name}/${testCase.descriptor.path(false).value}"

    fun specCodeRef(kclass: KClass<out Spec>): String = kclass.java.name

    /** Stable identifier ReportPortal uses to correlate a test across launches. */
    fun testCaseId(testCase: TestCase): String = codeRef(testCase)

    // --- Attributes ---

    /**
     * Test-level attributes: each Kotest tag plus the test's severity. Tags written as `key:value`
     * become keyed ReportPortal attributes (so RP's keyed filtering works); plain tags are
     * value-only. Severity is emitted as `severity=<LEVEL>` (the de-facto Allure/RP convention).
     */
    fun testAttributes(testCase: TestCase): Set<ItemAttributesRQ> {
        val tags = testCase.config.tags.map { attributeForTag(it.name, defaultKey = null) }
        val extras = buildList {
            add(ItemAttributesRQ("severity", testCase.config.severity.name))
            // Tests configured to run multiple times (flaky-detection); surface the count for filtering.
            if (testCase.config.invocations > 1) {
                add(ItemAttributesRQ("invocations", testCase.config.invocations.toString()))
            }
        }
        return (tags + extras).toSet()
    }

    /**
     * Spec-level attributes derived from the `@Tags` class annotation. Values written as `key:value`
     * become keyed attributes; plain values fall back to the `tag` key. Instance-level `tags { }` DSL
     * config is intentionally not read here: spec items are created in `prepareSpec`, which only
     * provides the [KClass], not a constructed [Spec] instance.
     */
    fun specAttributes(kclass: KClass<out Spec>): Set<ItemAttributesRQ> =
        kclass.java.getAnnotation(Tags::class.java)
            ?.values
            ?.filter { it.isNotBlank() }
            ?.map { attributeForTag(it, defaultKey = SPEC_TAG_KEY) }
            ?.toSet()
            ?: emptySet()

    /**
     * Turns a tag string into an attribute: `"team:payments"` -> key `team`, value `payments`;
     * otherwise the whole string is the value under [defaultKey] (which may be `null` for a
     * value-only attribute). A leading/trailing `:` is treated as malformed and kept as the value.
     */
    fun attributeForTag(tag: String, defaultKey: String?): ItemAttributesRQ {
        val idx = tag.indexOf(':')
        return if (idx in 1 until tag.length - 1) {
            ItemAttributesRQ(tag.substring(0, idx).trim(), tag.substring(idx + 1).trim())
        } else {
            ItemAttributesRQ(defaultKey, tag)
        }
    }
}
