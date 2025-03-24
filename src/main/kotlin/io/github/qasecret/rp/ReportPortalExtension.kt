package io.github.qasecret.rp

import com.epam.reportportal.listeners.ItemStatus
import com.epam.reportportal.listeners.LogLevel
import com.epam.reportportal.service.Launch
import com.epam.reportportal.service.LoggingContext
import com.epam.reportportal.service.ReportPortal
import com.epam.ta.reportportal.ws.model.FinishExecutionRQ
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ
import com.epam.ta.reportportal.ws.model.StartTestItemRQ
import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ
import io.kotest.core.Tag
import io.kotest.core.descriptors.Descriptor
import io.kotest.core.listeners.ProjectListener
import io.kotest.core.listeners.TestListener
import io.kotest.core.spec.Spec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.core.test.TestType
import io.reactivex.Maybe
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference


class ReportPortalExtension : TestListener, ProjectListener {
    companion object {
        private const val SUITE_NAME = "Test Suite"
        private const val DEFAULT_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1000L
        private val logger = LoggerFactory.getLogger(ReportPortalExtension::class.java)
    }

    private val reportPortal: ReportPortal by lazy { ReportPortal.builder().build() }
    private val suiteId = AtomicReference<Maybe<String>>(null)
    private val testItemIds = ConcurrentHashMap<String, Maybe<String>>()
    private val testStatuses = ConcurrentHashMap<String, ItemStatus>()
    private val launch = AtomicReference<Launch>()
    private val isInitialized = AtomicBoolean(false)
    private val projectFinished = AtomicBoolean(false)

    private val currentTime: Date
        get() = Calendar.getInstance().time.apply { time = (time / 1000) * 1000 }


    override suspend fun beforeProject() {
        try {
            if (isInitialized.compareAndSet(false, true)) {
                logger.debug("Initializing ReportPortal project")
                initializeLaunch()
                initializeSuite()
                logger.info("Successfully initialized ReportPortal launch and suite")
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize project", e)
        }
    }

    private fun setLoggingContext(itemId: Maybe<String>) {
        launch.get()?.let { l ->
            reportPortal.client?.let { client ->
                LoggingContext.init(
                    l.launch, itemId, client, Schedulers.io(), reportPortal.parameters
                )
            }
        }
    }

    private suspend fun initializeLaunch() {
        val rq = StartLaunchRQ().apply {
            name = reportPortal.parameters.launchName
            startTime = currentTime
            mode = reportPortal.parameters.launchRunningMode
            attributes = reportPortal.parameters.attributes
        }

        retryOnFailure(DEFAULT_RETRY_ATTEMPTS) {
            launch.set(reportPortal.newLaunch(rq))
            launch.get().start()
        }
    }

    private suspend fun initializeSuite() {
        val rq = StartTestItemRQ().apply {
            name = SUITE_NAME
            startTime = currentTime
            type = "SUITE"
            description = "Main Test Suite"
            attributes = setOf(ItemAttributesRQ("suite", "main"))
            isHasStats = true
        }

        launch.get()?.let { l ->
            retryOnFailure(DEFAULT_RETRY_ATTEMPTS) {
                val id = l.startTestItem(rq)
                suiteId.set(id)
                setLoggingContext(id)
            }
        }
    }

    override suspend fun afterProject() {
        try {
            if (projectFinished.compareAndSet(false, true)) {
                finishSuite()
                finishLaunch()
                cleanup()
                logger.info("Successfully finished ReportPortal reporting")
            }
        } catch (e: Exception) {
            logger.error("Failed to finish project", e)
        }
    }

    private fun finishSuite() {
        suiteId.get()?.let { id ->
            val rq = FinishTestItemRQ().apply {
                endTime = currentTime
                status = determineOverallStatus()
            }
            launch.get()?.finishTestItem(id, rq)
        }
    }


    private fun finishLaunch() {
        launch.get()?.let { l ->
            val rq = FinishExecutionRQ().apply {
                endTime = currentTime
            }
            l.finish(rq)
        }
    }

    private fun cleanup() {
        testItemIds.clear()
        testStatuses.clear()
        launch.set(null)
        suiteId.set(null)
        isInitialized.set(false)
        projectFinished.set(false)
    }

    private fun logTestEvent(message: String, level: LogLevel = LogLevel.DEBUG) {
        emitLog { uuid ->
            SaveLogRQ().apply {
                logTime = currentTime
                this.level = level.name
                this.message = message
                itemUuid = uuid
            }
        }
    }

    private fun emitLog(logSupplier: (String) -> SaveLogRQ) {
        try {
            val context = LoggingContext.context()
            if (context != null) {
                ReportPortal.emitLog(logSupplier)
            } else {
                logger.warn("No logging context available")
            }
        } catch (e: Exception) {
            logger.error("Failed to emit log", e)
        }
    }

    override suspend fun beforeTest(testCase: TestCase) {
        try {
            val testId = getTestItemId(testCase)
            if (!testItemIds.containsKey(testId)) {
                startTestItem(testCase)
            }
        } catch (e: Exception) {
            logger.error("Failed to start test: ${testCase.name.testName}", e)
        }
    }

    private fun startTestItem(testCase: TestCase) {
        val testType = if (testCase.type == TestType.Test) "TEST" else "BEFORE_METHOD"
        val parentId = getParentId(testCase)

        val rq = StartTestItemRQ().apply {
            name = formatTestName(testCase)
            startTime = currentTime
            type = testType
            codeRef = "${testCase.spec.javaClass.name}.${testCase.name.testName}"
            isHasStats = true
            attributes = testCase.config.tags.map { ItemAttributesRQ(null, it.name) }.toSet()
        }

        launch.get()?.let { l ->
            val itemId = l.startTestItem(parentId, rq)
            testItemIds[getTestItemId(testCase)] = itemId
            setLoggingContext(itemId)
            logTestEvent("Starting test: ${testCase.name.testName}")
        }
    }

    override suspend fun beforeSpec(spec: Spec) {
        try {
            val specId = getSpecItemId(spec)
            if (!testItemIds.containsKey(specId)) {
                startSpecItem(spec)
            }
        } catch (e: Exception) {
            logger.error("Failed to start spec: ${spec.javaClass.name}", e)
        }
    }


    private fun startSpecItem(spec: Spec) {
        val rq = StartTestItemRQ().apply {
            name = spec.javaClass.name
            startTime = currentTime
            type = "TEST"
            codeRef = spec.javaClass.name
            attributes = extractSpecAttributes(spec)
            isHasStats = true
        }

        launch.get()?.let { l ->
            val itemId = l.startTestItem(suiteId.get(), rq)
            testItemIds[getSpecItemId(spec)] = itemId
            setLoggingContext(itemId)
            logTestEvent("Starting specification: ${spec.javaClass.name}")
        }
    }


    override suspend fun afterTest(testCase: TestCase, result: TestResult) {
        try {
            val testId = getTestItemId(testCase)
            val itemId = testItemIds.remove(testId) ?: return
            val status = resolveTestStatus(result)
            testStatuses[testId] = status
            if (result is TestResult.Error || result is TestResult.Failure) {
                logTestFailure(testCase, result)
            }
            finishTestItem(itemId, result)
            logTestEvent("Test finished: ${testCase.name.testName} with status: $status")
        } catch (e: Exception) {
            logger.error("Failed to finish test: ${testCase.name.testName}", e)
        } finally {
            LoggingContext.complete()
        }
    }

    private fun finishTestItem(itemId: Maybe<String>, result: TestResult) {
        val rq = FinishTestItemRQ().apply {
            endTime = currentTime
            status = resolveTestStatus(result).name
            if (result is TestResult.Failure || result is TestResult.Error) {
                description = result.reasonOrNull
            }
        }

        launch.get()?.finishTestItem(itemId, rq)
    }


    private fun logTestFailure(testCase: TestCase, result: TestResult) {
        val message = buildString {
            append("Test failed: ")
            when (result) {
                is TestResult.Error -> {
                    append(testCase.name.originalName).append("\n")
                    result.cause.stackTraceToString().let { append("\nStacktrace:\n$it") }
                }

                is TestResult.Failure -> {
                    append(testCase.name.originalName).append("\n")
                    append(result.cause)
                    result.cause.stackTraceToString().let { append("\nStacktrace:\n$it") }
                }

                else -> append(testCase.name.testName).append("\n").append("Unknown failure")
            }
        }
        logTestEvent(message, LogLevel.ERROR)
    }


    private fun resolveTestStatus(result: TestResult): ItemStatus = when (result) {
        is TestResult.Success -> ItemStatus.PASSED
        is TestResult.Error, is TestResult.Failure -> ItemStatus.FAILED
        is TestResult.Ignored -> ItemStatus.SKIPPED
    }

    private fun determineOverallStatus(): String =
        if (testStatuses.values.any { it == ItemStatus.FAILED }) ItemStatus.FAILED.name
        else ItemStatus.PASSED.name

    private fun getParentId(testCase: TestCase): Maybe<String>? {
        return when (val parentDescriptor = (testCase.descriptor as? Descriptor.TestDescriptor)?.parent) {
            is Descriptor.TestDescriptor -> {
                testItemIds[getTestItemIdFromDescriptor(parentDescriptor)]
            }

            is Descriptor.SpecDescriptor -> {
                testItemIds[getSpecItemId(testCase.spec)]
            }

            null -> suiteId.get()
            else -> suiteId.get()
        }
    }

    private fun getTestItemIdFromDescriptor(descriptor: Descriptor.TestDescriptor): String =
        "test:${descriptor.id.value}"

    private fun getSpecItemId(spec: Spec): String = "spec:${spec.javaClass.name}#${spec.hashCode()}"

    private fun getTestItemId(testCase: TestCase): String = getTestItemIdFromDescriptor(testCase.descriptor)

    private fun formatTestName(testCase: TestCase): String = buildString {
        testCase.name.prefix?.let { append(it.dropLast(2)).append(" ") }
        append(testCase.name.testName)
    }

    private fun extractSpecAttributes(spec: Spec): Set<ItemAttributesRQ> =
        spec.javaClass.annotations.filterIsInstance<Tag>().map { ItemAttributesRQ("tag", it.name) }.toSet()

    private suspend fun <T> retryOnFailure(
        maxAttempts: Int, delayMs: Long = RETRY_DELAY_MS, block: suspend () -> T
    ): T {
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                logger.warn("Attempt ${attempt + 1} failed", e)
                if (attempt < maxAttempts - 1) delay(delayMs)
                else throw e
            }
        }
        throw IllegalStateException("All attempts failed")
    }
}