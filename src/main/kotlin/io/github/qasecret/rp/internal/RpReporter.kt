package io.github.qasecret.rp.internal

import com.epam.reportportal.listeners.ItemStatus
import com.epam.reportportal.listeners.LogLevel
import com.epam.reportportal.service.Launch
import com.epam.reportportal.service.ReportPortal
import com.epam.ta.reportportal.ws.model.FinishExecutionRQ
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ
import com.epam.ta.reportportal.ws.model.StartTestItemRQ
import com.epam.ta.reportportal.ws.model.issue.Issue
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ
import io.github.qasecret.rp.RpConfig
import io.kotest.core.spec.Spec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestType
import io.kotest.engine.test.TestResult
import io.reactivex.Maybe
import org.slf4j.LoggerFactory
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

/**
 * Owns the ReportPortal [Launch] and the Kotest-to-ReportPortal item tree, and performs all
 * interaction with the ReportPortal client. Thread-safe: state is held in concurrent/atomic
 * containers, and logs are bound directly to their item UUID (no thread-local `LoggingContext`),
 * so it is safe under Kotest's coroutine-based, potentially parallel, execution.
 *
 * Errors are non-fatal: a single warning is emitted if the launch cannot be started, after which all
 * operations short-circuit so test execution is never affected.
 */
internal class RpReporter(
    private val rp: ReportPortal,
    private val config: RpConfig,
) {
    private companion object {
        private val logger = LoggerFactory.getLogger(RpReporter::class.java)
    }

    private val launchRef = AtomicReference<Launch?>(null)
    private val launchUuidRef = AtomicReference<Maybe<String>?>(null)
    private val rootSuiteId = AtomicReference<Maybe<String>?>(null)

    /** Item-tree keys (see [RpMapper]) -> started ReportPortal item. */
    private val itemIds = ConcurrentHashMap<String, Maybe<String>>()

    /**
     * Container nodes whose RP item is deferred until we know if they actually have children. Kotest 6
     * types every `withData` data row as [TestType.Container] — including a row whose block only asserts
     * (no nested tests), which is really a *leaf*. We can't tell the two apart at `beforeTest`, so we
     * park containers here and create the RP item lazily: as a SUITE the moment a child appears (so the
     * real tests roll up into launch statistics), or as a leaf at finish if none ever does. Creating a
     * childless `withData` row eagerly as a SUITE would leave an empty suite that contributes no pass/
     * fail counts; creating one eagerly as a leaf would force any later children to nest under a STEP
     * (which RP never rolls up). Deferral resolves both.
     */
    private val pending = ConcurrentHashMap<String, TestCase>()

    /**
     * Containers still `pending` at their own `finishTest` (no executed child materialized them). They
     * might still own **ignored** children, reported via [reportIgnored] as they are encountered, which
     * must be able to promote the node to a SUITE. So we stash the result here and finish the node in
     * `finishSpec` (after ignored children have had their chance), rather than fixing it as a leaf
     * prematurely (which would re-nest the skipped children under a STEP).
     */
    private val deferredFinish = ConcurrentHashMap<String, Pair<TestCase, TestResult>>()

    /** Keys already finished, to avoid double-finishing and to detect un-reported ignored tests. */
    private val reported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val started = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val disabled = AtomicBoolean(false)
    private val anyFailure = AtomicBoolean(false)

    private fun now() = Date()

    private fun activeLaunch(): Launch? = if (disabled.get()) null else launchRef.get()

    fun startLaunch() {
        if (!started.compareAndSet(false, true)) return
        val params = rp.parameters
        if (params.enable == false) {
            logger.warn("ReportPortal is disabled (rp.enable=false); Kotest reporting is skipped.")
            disabled.set(true)
            return
        }
        try {
            val externalUuid = params.launchUuid?.takeIf { it.isNotBlank() }
            if (externalUuid != null) {
                // Attaching to a launch created elsewhere (e.g. a CI orchestrator pre-created it and
                // passed rp.launch.uuid to every shard). Ensure creation-skip so the client uses the
                // existing launch and finish() flushes our items WITHOUT closing the shared launch
                // (the owner closes it). finish() must still be called so pending items are flushed.
                params.isLaunchUuidCreationSkip = true
            }

            val rq = StartLaunchRQ().apply {
                name = params.launchName
                startTime = now()
                mode = params.launchRunningMode
                attributes = params.attributes.orEmpty() + RpAgent.launchAttributes()
                // Rerun mode (rp.rerun / rp.rerun.of): merge into the history of a previous launch.
                isRerun = params.isRerun
                params.rerunOf?.takeIf { it.isNotBlank() }?.let { rerunOf = it }
            }
            // With an external launchUuid set, start() returns it without calling startLaunch.
            val launch = rp.newLaunch(rq)
            launchRef.set(launch)
            launchUuidRef.set(launch.start())

            // Skip the synthetic root suite when attaching externally to avoid one root per shard.
            if (externalUuid == null && config.syntheticRootSuite) {
                val suiteRq = StartTestItemRQ().apply {
                    name = config.rootSuiteName
                    startTime = now()
                    type = "SUITE"
                    isHasStats = true
                }
                rootSuiteId.set(launch.startTestItem(suiteRq))
            }
        } catch (e: Exception) {
            logger.warn("Failed to start ReportPortal launch; Kotest reporting disabled for this run.", e)
            disabled.set(true)
        }
    }

    fun startSpec(kclass: KClass<out Spec>) {
        val launch = activeLaunch() ?: return
        val key = RpMapper.specKey(kclass)
        if (itemIds.containsKey(key)) return
        try {
            val rq = StartTestItemRQ().apply {
                name = RpMapper.specName(kclass)
                startTime = now()
                type = "SUITE"
                codeRef = RpMapper.specCodeRef(kclass)
                attributes = RpMapper.specAttributes(kclass)
                isHasStats = true
            }
            val parent = rootSuiteId.get()
            val id = if (parent != null) launch.startTestItem(parent, rq) else launch.startTestItem(rq)
            itemIds[key] = id
        } catch (e: Exception) {
            logger.error("Failed to start spec ${kclass.java.name}", e)
        }
    }

    fun startTest(testCase: TestCase) {
        val launch = activeLaunch() ?: return
        val key = RpMapper.testKey(testCase)
        if (itemIds.containsKey(key) || pending.containsKey(key)) return
        try {
            // Defer containers: a `TestType.Container` might be a real suite (it gets children) or a
            // childless `withData` data row that is really a leaf. We can't tell yet, so park it and
            // let the first child (or the finish) decide its RP item type. Leaf tests are unambiguous
            // and created immediately.
            if (testCase.type == TestType.Container) {
                pending[key] = testCase
                // Retain logs emitted from the container body before its item is materialized, so they
                // aren't dropped (flushed onto the item when createItem registers its log context).
                if (rp.client != null) RpLog.beginBuffering(key)
                return
            }
            createItem(launch, testCase, key, RpMapper.itemType(testCase, config))
        } catch (e: Exception) {
            logger.error("Failed to start test ${testCase.name.name}", e)
        }
    }

    fun finishTest(testCase: TestCase, result: TestResult) {
        val launch = activeLaunch() ?: return
        val key = RpMapper.testKey(testCase)
        val itemId = itemIds[key]
        if (itemId == null) {
            // A container still in `pending` here had no *executed* children, but may still own
            // *ignored* ones (reported via reportIgnored) that must promote it to a SUITE. Defer its
            // finish to finishSpec rather than fixing it as a leaf now (which would re-nest the skipped
            // children under a STEP). beforeTest never fires for ignored tests, so a missing,
            // non-pending key is simply nothing to finish.
            if (pending.containsKey(key)) deferredFinish[key] = testCase to result
            return
        }
        finishItem(launch, testCase, key, itemId, result)
    }

    /** Finishes an already-created item: failure log + status + issue, guarded and once-only. */
    private fun finishItem(launch: Launch, testCase: TestCase, key: String, itemId: Maybe<String>, result: TestResult) {
        if (!reported.add(key)) return
        RpLog.unregister(key)
        try {
            val status = RpMapper.status(result)
            if (status == ItemStatus.FAILED) anyFailure.set(true)

            val cause = causeOf(result)
            if (cause != null) {
                sendLog(itemId, failureMessage(result, cause), LogLevel.ERROR)
            }

            val rq = FinishTestItemRQ().apply {
                endTime = now()
                this.status = status.name
                if (cause != null) {
                    description = result.reasonOrNull?.lineSequence()?.firstOrNull()
                    defectTypeFor(testCase, result)?.let { defect ->
                        issue = Issue().apply {
                            issueType = defect
                            comment = result.reasonOrNull
                        }
                    }
                }
            }
            launch.finishTestItem(itemId, rq)
        } catch (e: Exception) {
            logger.error("Failed to finish test ${testCase.name.name}", e)
        }
    }

    /**
     * Logs a per-invocation marker onto the test item for multi-invocation (flaky-detection) tests.
     * Kotest exposes no per-invocation result, so this records how many of N repetitions ran
     * (the last marker before a failure indicates which invocation was flaky).
     */
    fun logInvocation(testCase: TestCase, invocation: Int) {
        val invocations = testCase.config?.invocations ?: 1
        if (disabled.get() || invocations <= 1) return
        val itemId = itemIds[RpMapper.testKey(testCase)] ?: return
        sendLog(itemId, "Invocation ${invocation + 1} of $invocations", LogLevel.INFO)
    }

    /**
     * Reports a Kotest test that was disabled/ignored as a SKIPPED item. Kotest 6 delivers these
     * through the dedicated [io.kotest.core.listeners.IgnoredTestListener] (they no longer appear in
     * `finalizeSpec`'s results map nor fire `afterTest`/`beforeTest`), so [ReportPortalExtension] wires
     * this from `ignoredTest`. Reporting an ignored child of a deferred `withData` container promotes
     * that container to a SUITE (via `resolveParent`), keeping the invariant that nothing nests under a
     * leaf STEP. No-op when [RpConfig.reportIgnored] is disabled.
     */
    fun reportIgnored(testCase: TestCase, reason: String?) {
        if (!config.reportIgnored) return
        val launch = activeLaunch() ?: return
        try {
            // reportSkipped's `reported.add` is the single, atomic once-only guard.
            reportSkipped(launch, testCase, reason)
        } catch (e: Exception) {
            logger.error("Failed to report ignored test ${testCase.name.name}", e)
        }
    }

    fun finishSpec(kclass: KClass<out Spec>, results: Map<TestCase, TestResult>) {
        val launch = activeLaunch() ?: return
        try {
            // Finish any container deferred at finishTest. Ignored children (reported eagerly via
            // reportIgnored) have by now promoted real container nodes to SUITEs; whatever is still
            // only pending genuinely had no children and is materialized here as a real leaf.
            finishDeferred(launch)

            val specKey = RpMapper.specKey(kclass)
            val specId = itemIds[specKey] ?: return
            if (!reported.add(specKey)) return
            launch.finishTestItem(
                specId,
                FinishTestItemRQ().apply {
                    endTime = now()
                    status = specStatus(results).name
                },
            )
        } catch (e: Exception) {
            logger.error("Failed to finish spec ${kclass.java.name}", e)
        }
    }

    fun finishLaunch() {
        if (!finished.compareAndSet(false, true)) return
        val launch = activeLaunch() ?: return
        try {
            rootSuiteId.get()?.let { rootId ->
                val status = if (anyFailure.get()) ItemStatus.FAILED else ItemStatus.PASSED
                launch.finishTestItem(
                    rootId,
                    FinishTestItemRQ().apply {
                        endTime = now()
                        this.status = status.name
                    },
                )
            }
            // Always call finish(): it flushes our pending items. The client only sends the actual
            // finish-launch request when we own the launch; with an external launchUuid +
            // creation-skip it flushes but leaves the shared launch open for its owner to close.
            launch.finish(FinishExecutionRQ().apply { endTime = now() })
        } catch (e: Exception) {
            logger.error("Failed to finish ReportPortal launch", e)
        } finally {
            itemIds.clear()
            pending.clear()
            deferredFinish.clear()
            reported.clear()
            RpLog.clear()
        }
    }

    // --- helpers ---

    /**
     * Creates the ReportPortal item for [testCase] under [key] with the given [type], records it, and
     * registers its log context. Idempotent and safe under concurrent children: the parent is resolved
     * before locking, then a double-checked put guarantees a single creation per key.
     */
    private fun createItem(launch: Launch, testCase: TestCase, key: String, type: String): Maybe<String> {
        itemIds[key]?.let { return it }
        val rq = StartTestItemRQ().apply {
            name = RpMapper.displayName(testCase)
            startTime = now()
            this.type = type
            codeRef = RpMapper.codeRef(testCase)
            testCaseId = RpMapper.testCaseId(testCase)
            attributes = RpMapper.testAttributes(testCase)
            isHasStats = true
        }
        // Resolve the parent outside the lock (it may itself create an ancestor item on demand).
        val parent = resolveParent(launch, testCase)
        synchronized(itemIds) {
            itemIds[key]?.let { return it }
            val id = if (parent != null) launch.startTestItem(parent, rq) else launch.startTestItem(rq)
            itemIds[key] = id
            pending.remove(key)
            rp.client?.let { RpLog.register(key, RpLogContext(it, launchUuidRef.get(), id, rp.parameters)) }
            return id
        }
    }

    /**
     * Finishes the containers deferred at [finishTest]. By call time, ignored children have already
     * promoted container nodes to SUITEs (in `itemIds`); a node still only in `pending` had no children
     * at all and is materialized here as a leaf.
     */
    private fun finishDeferred(launch: Launch) {
        deferredFinish.forEach { (key, deferred) ->
            val (testCase, result) = deferred
            val itemId = itemIds[key] ?: createItem(launch, testCase, key, RpMapper.leafType(config))
            finishItem(launch, testCase, key, itemId, result)
        }
        deferredFinish.clear()
    }

    private fun reportSkipped(launch: Launch, testCase: TestCase, reason: String?) {
        val key = RpMapper.testKey(testCase)
        if (!reported.add(key)) return
        val id = createItem(launch, testCase, key, RpMapper.leafType(config))
        launch.finishTestItem(
            id,
            FinishTestItemRQ().apply {
                endTime = now()
                status = ItemStatus.SKIPPED.name
                description = reason
            },
        )
    }

    private fun resolveParent(launch: Launch, testCase: TestCase): Maybe<String>? {
        val parentKey = RpMapper.parentKey(testCase)
        itemIds[parentKey]?.let { return it }
        // The parent is a deferred container that has now produced a child, so it is a real suite:
        // materialize it as a SUITE before nesting this child under it.
        pending[parentKey]?.let { return createItem(launch, it, parentKey, "SUITE") }
        // Defensive fallback (parent not yet recorded): nest under the spec, else the root suite.
        return itemIds[RpMapper.specKey(testCase.spec)] ?: rootSuiteId.get()
    }

    /** Resolver wins when set (null return = no defect); otherwise the configured default applies. */
    private fun defectTypeFor(testCase: TestCase, result: TestResult): String? =
        config.defectTypeResolver?.invoke(testCase, result) ?: config.failureDefectType.takeIf {
            config.defectTypeResolver == null
        }

    private fun causeOf(result: TestResult): Throwable? = when (result) {
        is TestResult.Failure -> result.cause
        is TestResult.Error -> result.cause
        else -> null
    }

    private fun failureMessage(result: TestResult, cause: Throwable): String = buildString {
        append("Test failed: ")
        append(result.reasonOrNull ?: cause.message ?: cause::class.java.name)
        append("\n\n")
        append(cause.stackTraceToString())
    }

    private fun specStatus(results: Map<TestCase, TestResult>): ItemStatus = when {
        results.values.any { it is TestResult.Failure || it is TestResult.Error } -> ItemStatus.FAILED
        results.values.any { it is TestResult.Success } -> ItemStatus.PASSED
        else -> ItemStatus.SKIPPED
    }

    /**
     * Sends a log bound directly to [itemId]. Bypasses `LoggingContext` (which is thread-local and
     * unreliable across Kotest's coroutine dispatch) by posting the [SaveLogRQ] with explicit item
     * and launch UUIDs.
     */
    private fun sendLog(itemId: Maybe<String>, message: String, level: LogLevel) {
        try {
            val uuid = itemId.blockingGet() ?: return
            val rq = SaveLogRQ().apply {
                this.message = message
                this.level = level.name
                logTime = now()
                itemUuid = uuid
                launchUuidRef.get()?.blockingGet()?.let { launchUuid = it }
            }
            rp.client?.log(rq)?.blockingGet()
        } catch (e: Exception) {
            logger.debug("Failed to send log to ReportPortal", e)
        }
    }
}
