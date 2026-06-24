package io.github.qasecret.rp

import com.epam.reportportal.listeners.ItemStatus
import io.github.qasecret.rp.internal.RpMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.test.TestResult
import io.kotest.matchers.shouldBe
import kotlin.time.Duration

/**
 * Pure unit tests for [RpMapper]. TestCase-dependent mappings (names, types, codeRef, parent links)
 * are covered end-to-end in [ReportPortalExtensionEngineTest] via the recorded ReportPortal requests.
 */
class RpMapperTest : FunSpec({

    test("status maps each TestResult variant to the right ReportPortal status") {
        RpMapper.status(TestResult.Success(Duration.ZERO)) shouldBe ItemStatus.PASSED
        RpMapper.status(TestResult.Failure(Duration.ZERO, AssertionError("boom"))) shouldBe ItemStatus.FAILED
        RpMapper.status(TestResult.Error(Duration.ZERO, RuntimeException("boom"))) shouldBe ItemStatus.FAILED
        RpMapper.status(TestResult.Ignored("skip")) shouldBe ItemStatus.SKIPPED
    }

    test("spec keys are stable and independent of instance hashCode") {
        val a = SampleSpec()
        val b = SampleSpec()
        RpMapper.specKey(a) shouldBe RpMapper.specKey(b)
        RpMapper.specKey(SampleSpec::class) shouldBe RpMapper.specKey(a)
        RpMapper.specKey(a) shouldBe "spec:${SampleSpec::class.java.name}"
    }

    test("spec name uses the simple class name and codeRef uses the FQCN") {
        RpMapper.specName(SampleSpec::class) shouldBe "SampleSpec"
        RpMapper.specCodeRef(SampleSpec::class) shouldBe SampleSpec::class.java.name
    }

    test("leaf type honors the configured LeafItemType") {
        RpMapper.leafType(RpConfig()) shouldBe "STEP"
        RpMapper.leafType(RpConfig(leafItemType = LeafItemType.TEST)) shouldBe "TEST"
    }

    test("attributeForTag parses key:value and falls back to the default key") {
        RpMapper.attributeForTag("team:payments", defaultKey = null).let {
            it.key shouldBe "team"
            it.value shouldBe "payments"
        }
        RpMapper.attributeForTag("fast", defaultKey = null).let {
            it.key shouldBe null
            it.value shouldBe "fast"
        }
        RpMapper.attributeForTag("smoke", defaultKey = "tag").let {
            it.key shouldBe "tag"
            it.value shouldBe "smoke"
        }
        // Malformed (leading colon) is kept as a value-only attribute, not split.
        RpMapper.attributeForTag(":oops", defaultKey = null).let {
            it.key shouldBe null
            it.value shouldBe ":oops"
        }
    }
}) {
    private class SampleSpec : FunSpec()
}
