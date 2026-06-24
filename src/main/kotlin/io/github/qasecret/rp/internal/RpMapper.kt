package io.github.qasecret.rp.internal

import com.epam.reportportal.listeners.ItemStatus
import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ
import io.github.qasecret.rp.LeafItemType
import io.github.qasecret.rp.RpConfig
import io.kotest.core.Tag
import io.kotest.core.annotation.Tags
import io.kotest.core.descriptors.Descriptor
import io.kotest.core.spec.Spec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestCaseSeverityLevel
import io.kotest.core.test.TestType
import io.kotest.engine.test.TestResult
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

    fun testKey(descriptor: Descriptor.TestDescriptor): String = "test:${descriptor.path().value}"

    /**
     * Key of the parent item this test should be nested under: the parent test when nested in a
     * container, or the spec when the test is at the root of the spec. A test descriptor's parent is
     * always one of these two (the `Descriptor` sealed hierarchy), so this never returns null.
     */
    fun parentKey(testCase: TestCase): String = when (val parent = testCase.descriptor.parent) {
        is Descriptor.TestDescriptor -> testKey(parent)
        is Descriptor.SpecDescriptor -> specKey(testCase.spec)
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
            append(name)
            suffix?.takeIf { it.isNotBlank() }?.let { append(it) }
        }
    }

    fun specName(kclass: KClass<out Spec>): String = kclass.simpleName ?: kclass.java.name

    // --- ReportPortal item types ---

    fun itemType(testCase: TestCase, config: RpConfig): String = when (testCase.type) {
        TestType.Container -> "SUITE"
        TestType.Test -> config.leafItemType.rpType
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
        "${testCase.spec::class.java.name}/${testCase.descriptor.path().value}"

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
        // Kotest 6's `TestConfig` is a sparse per-test override (it and its fields are nullable). Values
        // set on an enclosing container live on ancestor `TestCase`s, so resolve severity and tags by
        // walking the parent chain (falling back to the engine defaults), rather than reading only this
        // test's own override — which would drop inherited values.
        val tags = resolvedTags(testCase).map { attributeForTag(it.name, defaultKey = null) }
        val severity = resolvedSeverity(testCase)
        val invocations = testCase.config?.invocations ?: 1
        val extras = buildList {
            add(ItemAttributesRQ("severity", severity.name))
            // Tests configured to run multiple times (flaky-detection); surface the count for filtering.
            if (invocations > 1) {
                add(ItemAttributesRQ("invocations", invocations.toString()))
            }
        }
        return (tags + extras).toSet()
    }

    /** Effective severity: this test's override, else the nearest enclosing one, else the default. */
    private fun resolvedSeverity(testCase: TestCase): TestCaseSeverityLevel {
        var tc: TestCase? = testCase
        while (tc != null) {
            tc.config?.severity?.let { return it }
            tc = tc.parent
        }
        return TestCaseSeverityLevel.NORMAL
    }

    /** Effective tags: the union of this test's tags and those of every enclosing container. */
    private fun resolvedTags(testCase: TestCase): Set<Tag> {
        val tags = linkedSetOf<Tag>()
        var tc: TestCase? = testCase
        while (tc != null) {
            tc.config?.tags?.let { tags += it }
            tc = tc.parent
        }
        return tags
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
