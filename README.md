# ReportPortalExtension Integration with Kotest

This guide provides the steps to integrate **ReportPortal** with **Kotest** using the `ReportPortalExtension` 
extension. Follow these steps to configure your project for seamless reporting to ReportPortal.

---

## Prerequisites

Before proceeding, ensure you have the following:
- A running **ReportPortal** instance (either self-hosted or cloud-based).
- Access to **ReportPortal API** (you will need your project ID, endpoint, and other configuration details).
- **Kotest** already set up in your project.

---

## Setup Instructions

### 1. Add the `kotest-rp` Dependency

To integrate ReportPortal with Kotest, first, add the necessary dependencies in your `build.gradle.kts` or `build.gradle` file.

#### Example for Gradle (Kotlin DSL):

```kotlin
dependencies {
    testImplementation("io.github.qasecret:kotest-rp:<version>")
}
```

This library depends only on the ReportPortal client and `slf4j-api`; it ships **no logging backend**,
so you keep control of your own (logback, log4j2, …). Replace `<version>` with the latest version
available on Maven Central.

### 2. Configure your project config with the ReportPortal extension

```kotlin
class ProjectConfig : AbstractProjectConfig() {
    override fun extensions(): List<Extension> = listOf(ReportPortalExtension())
}
```

### 3. Provide ReportPortal connection settings

The no-arg `ReportPortalExtension()` reads the standard ReportPortal configuration: a
`reportportal.properties` file on the (test) classpath and/or `rp.*` system properties / environment
variables, for example:

```properties
rp.endpoint=https://your-reportportal-instance
rp.api.key=your-api-key
rp.project=your-project
rp.launch=Kotest Launch
```

## Reporting structure

Each Kotest spec is reported as a `SUITE` directly under the launch; container tests
(`describe`/`context`/`given`/`when`, …) become nested `SUITE`s and leaf tests become `STEP`s.
Ignored/disabled tests are reported as `SKIPPED`, and failures carry the stacktrace as an error log
plus a defect type (To Investigate by default).

**Attributes & metadata:**
- Kotest test **tags** become ReportPortal attributes. A tag written as `key:value`
  (e.g. `NamedTag("team:payments")`) becomes a keyed attribute; a plain tag becomes a value-only
  attribute. Spec-level `@Tags(...)` become `tag` attributes on the spec.
- Test **severity** (`config(severity = ...)`) is reported as a `severity=<LEVEL>` attribute.
- **Multi-invocation** tests (`config(invocations = N)`, used for flaky detection) get an
  `invocations=N` attribute and a `Invocation k of N` log marker per run, so you can see how many
  repetitions ran before a flaky failure. (Kotest 5.9 exposes no per-invocation result, so these are
  markers rather than ReportPortal retry groups.)
- Each launch is tagged with `agent` (`kotest-rp|<version>`), `os`, and `jvm` attributes for filtering.

## Logs & attachments

Attach extra logs and files (screenshots, payloads, …) to the currently running test from inside the
test body via `ReportPortalLogs`:

```kotlin
test("uploads a screenshot on the login page") {
    ReportPortalLogs.info("navigating to the login page")
    ReportPortalLogs.attach(File("build/screenshots/login.png"), message = "login page")
    // or from raw bytes:
    ReportPortalLogs.attach("payload.json", responseBytes, "application/json")
    ReportPortalLogs.warn("done")
}
```

Logs are bound to the running test's item. The API is best-effort and non-fatal: if a custom
multi-threaded dispatcher has moved execution off the test's thread, the call is a no-op rather than
an error.

## CI: reruns & distributed launches

- **Rerun** — set `rp.rerun=true` (and optionally `rp.rerun.of=<launchUuid>`) to merge a run into the
  history of a previous launch.
- **Shared launch across CI shards** — two options:
  - *Client join*: set `rp.client.join=true` on every concurrent shard; the ReportPortal client
    elects one launch and the others join it automatically (no code changes needed).
  - *External launch UUID*: have an orchestrator create the launch, pass its UUID to every shard via
    `rp.launch.uuid=<uuid>`, and let the orchestrator finish it. Each shard reports its items into the
    shared launch and flushes them, but never creates or closes the launch.

## Customizing behavior

Pass an `RpConfig` to customize reporting:

```kotlin
override fun extensions(): List<Extension> = listOf(
    ReportPortalExtension(
        RpConfig(
            syntheticRootSuite = false,        // true = nest all specs under one root SUITE
            leafItemType = LeafItemType.STEP,  // or LeafItemType.TEST
            reportIgnored = true,              // report ignored tests as SKIPPED
            failureDefectType = RpDefect.TO_INVESTIGATE, // default defect for failures; null to disable
            // Optional: choose the defect type per failure (overrides failureDefectType):
            defectTypeResolver = { _, result ->
                if (result is TestResult.Failure) RpDefect.PRODUCT_BUG else RpDefect.AUTOMATION_BUG
            },
        )
    )
)
```

