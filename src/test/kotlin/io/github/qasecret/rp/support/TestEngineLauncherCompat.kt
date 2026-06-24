package io.github.qasecret.rp

import io.kotest.core.spec.Spec
import io.kotest.core.spec.SpecRef
import io.kotest.engine.EngineResult
import io.kotest.engine.TestEngineLauncher
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass

/**
 * Test-only compatibility shims for Kotest 6 [TestEngineLauncher] API renames:
 *  - `withClasses(...)` was removed in favor of [TestEngineLauncher.withSpecRefs] taking [SpecRef]s;
 *    this wraps each spec class in a [SpecRef.Reference].
 *  - the synchronous `launch()` terminal was replaced by the suspending [TestEngineLauncher.execute];
 *    these tests drive it from ordinary (non-suspend) helpers, so this bridges via [runBlocking].
 */
fun TestEngineLauncher.withClasses(vararg classes: KClass<out Spec>): TestEngineLauncher =
    withSpecRefs(classes.map { SpecRef.Reference(it) })

fun TestEngineLauncher.launch(): EngineResult = runBlocking { execute() }
