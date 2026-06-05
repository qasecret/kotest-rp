package io.github.qasecret.rp.support

import com.epam.reportportal.listeners.ListenerParameters
import com.epam.reportportal.service.ReportPortal
import com.epam.reportportal.service.ReportPortalClient
import com.epam.ta.reportportal.ws.model.BatchSaveOperatingRS
import com.epam.ta.reportportal.ws.model.EntryCreatedAsyncRS
import com.epam.ta.reportportal.ws.model.FinishExecutionRQ
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ
import com.epam.ta.reportportal.ws.model.OperationCompletionRS
import com.epam.ta.reportportal.ws.model.StartTestItemRQ
import com.epam.ta.reportportal.ws.model.TestItemResource
import com.epam.ta.reportportal.ws.model.item.ItemCreatedRS
import com.epam.ta.reportportal.ws.model.launch.LaunchResource
import com.epam.ta.reportportal.ws.model.launch.MergeLaunchesRQ
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRS
import com.epam.ta.reportportal.ws.model.launch.UpdateLaunchRQ
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ
import com.epam.ta.reportportal.ws.model.project.config.ProjectSettingsResource
import io.reactivex.Maybe
import okhttp3.MultipartBody
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory [ReportPortalClient] that records every request the real `LaunchImpl` makes. Drives the
 * extension through `ReportPortal.create(client, params)` so the tests exercise the genuine
 * ReportPortal launch/item lifecycle, then assert against the recorded calls.
 */
class RecordingReportPortalClient : ReportPortalClient {

    data class StartedItem(val uuid: String, val parentUuid: String?, val rq: StartTestItemRQ)
    data class FinishedItem(val uuid: String, val rq: FinishTestItemRQ)

    val launches = CopyOnWriteArrayList<StartLaunchRQ>()
    val startedItems = CopyOnWriteArrayList<StartedItem>()
    val finishedItems = CopyOnWriteArrayList<FinishedItem>()
    val logs = CopyOnWriteArrayList<SaveLogRQ>()
    val finishedLaunches = CopyOnWriteArrayList<String>()

    private val counter = AtomicInteger()

    override fun startLaunch(rq: StartLaunchRQ): Maybe<StartLaunchRS> {
        launches.add(rq)
        return Maybe.just(StartLaunchRS("launch-uuid", 1L))
    }

    override fun startTestItem(rq: StartTestItemRQ): Maybe<ItemCreatedRS> = recordStart(null, rq)

    override fun startTestItem(parentUuid: String, rq: StartTestItemRQ): Maybe<ItemCreatedRS> =
        recordStart(parentUuid, rq)

    private fun recordStart(parent: String?, rq: StartTestItemRQ): Maybe<ItemCreatedRS> {
        val uuid = "item-${counter.incrementAndGet()}"
        startedItems.add(StartedItem(uuid, parent, rq))
        return Maybe.just(ItemCreatedRS().apply { id = uuid })
    }

    override fun finishTestItem(uuid: String, rq: FinishTestItemRQ): Maybe<OperationCompletionRS> {
        finishedItems.add(FinishedItem(uuid, rq))
        return Maybe.just(OperationCompletionRS("ok"))
    }

    override fun finishLaunch(uuid: String, rq: FinishExecutionRQ): Maybe<OperationCompletionRS> {
        finishedLaunches.add(uuid)
        return Maybe.just(OperationCompletionRS("ok"))
    }

    override fun log(rq: SaveLogRQ): Maybe<EntryCreatedAsyncRS> {
        logs.add(rq)
        return Maybe.just(EntryCreatedAsyncRS("log-${counter.incrementAndGet()}"))
    }

    override fun log(parts: MutableList<MultipartBody.Part>): Maybe<BatchSaveOperatingRS> =
        Maybe.just(BatchSaveOperatingRS())

    // Unused by this extension — return well-formed empty/no-op responses.
    override fun mergeLaunches(rq: MergeLaunchesRQ): Maybe<LaunchResource> = Maybe.just(LaunchResource())
    override fun updateLaunch(uuid: String, rq: UpdateLaunchRQ): Maybe<LaunchResource> = Maybe.just(LaunchResource())
    override fun getLaunchByUuid(uuid: String): Maybe<LaunchResource> = Maybe.just(LaunchResource())
    override fun getItemByUuid(uuid: String): Maybe<TestItemResource> = Maybe.just(TestItemResource())
    override fun getProjectSettings(): Maybe<ProjectSettingsResource> = Maybe.just(ProjectSettingsResource())

    // --- assertion helpers ---

    fun started(name: String): StartedItem = startedItems.first { it.rq.name == name }
    fun startedOrNull(name: String): StartedItem? = startedItems.firstOrNull { it.rq.name == name }
    fun childrenOf(uuid: String): List<StartedItem> = startedItems.filter { it.parentUuid == uuid }
    fun finishOf(uuid: String): FinishedItem = finishedItems.first { it.uuid == uuid }
    fun logsFor(uuid: String): List<SaveLogRQ> = logs.filter { it.itemUuid == uuid }

    companion object {
        /** Builds a real ReportPortal backed by [client], enabled, with a launch name set. */
        fun reportPortal(client: RecordingReportPortalClient): ReportPortal =
            reportPortal(
                client,
                ListenerParameters().apply {
                    launchName = "kotest-rp-test"
                    enable = true
                },
            )

        /** Builds a real ReportPortal backed by [client] with the supplied [params]. */
        fun reportPortal(client: RecordingReportPortalClient, params: ListenerParameters): ReportPortal =
            ReportPortal.create(client, params)
    }
}
