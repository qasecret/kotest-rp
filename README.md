# kotest-rp — ReportPortal extension for Kotest

`kotest-rp` reports [Kotest](https://kotest.io) test runs to [ReportPortal](https://reportportal.io).
Register one extension and your launches, specs, tests, statuses, logs, and attachments show up in
ReportPortal with a faithful, nested structure.

## Features

- **Faithful tree** — each spec is a `SUITE`, containers nest as `SUITE`s, leaf tests are `STEP`s
  (configurable), at any depth.
- **All Kotest spec styles** — Fun, String, Should, Describe, Behavior, Word, Feature, Expect, Free,
  and Annotation specs, with their natural affixes (`Given:`/`Describe:`/`Feature:` …) preserved.
- **Accurate statuses** — passed / failed / skipped; ignored & disabled tests are reported too.
- **Rich failures** — stacktrace attached as an error log, plus a configurable ReportPortal defect type.
- **Attributes & metadata** — Kotest tags (incl. `key:value`), test severity, and invocation counts.
- **Logs & attachments** — attach logs and files (screenshots, payloads) to the running test.
- **Flaky detection** — per-invocation markers for multi-invocation tests.
- **CI-ready** — rerun mode and distributed launches (client-join or external launch UUID).
- **Safe by design** — depends only on the ReportPortal client + `slf4j-api` (no logging backend
  forced on you); all reporting is non-fatal, so it never breaks your build.

## Requirements

- Kotest **5.9.x**
- A reachable ReportPortal instance (self-hosted or cloud) and an API key.

## Installation

The extension is a test-time dependency:

```kotlin
dependencies {
    testImplementation("io.github.qasecret:kotest-rp:<version>")
}
```

Replace `<version>` with the latest on Maven Central. The published artifact pulls in only
`com.epam.reportportal:client-java` and `org.slf4j:slf4j-api` — pick your own SLF4J backend.

## Quick start

### 1. Register the extension

```kotlin
class ProjectConfig : AbstractProjectConfig() {
    override fun extensions(): List<Extension> = listOf(ReportPortalExtension())
}
```

### 2. Provide connection settings

The no-arg `ReportPortalExtension()` reads the standard ReportPortal configuration — a
`reportportal.properties` on the (test) classpath and/or `rp.*` system properties / environment
variables:

```properties
rp.endpoint = https://your-reportportal-instance
rp.api.key  = your-api-key
rp.project  = your-project
rp.launch   = Kotest Launch
# rp.enable = false   # turn reporting off without removing the extension
```

That's it — run your tests and a launch appears in ReportPortal.

## Reporting structure

```
Launch
 └─ com.acme.LoginSpec            (SUITE)
     ├─ "logs in"                 (STEP)
     └─ Describe: validation      (SUITE)     # nested container
         └─ "rejects empty input" (STEP)
```

- **Spec** → `SUITE` directly under the launch (set `syntheticRootSuite = true` to nest everything
  under one root suite instead).
- **Container test** (`context`/`describe`/`given`/`when`/`feature`/…) → `SUITE`.
- **Leaf test** → `STEP` (or `TEST`, see `leafItemType`).
- **Ignored / disabled** test → `SKIPPED`.
- **Failure** → `FAILED` with the stacktrace as an `ERROR` log and a defect type (To Investigate by
  default).

## Attributes & metadata

- **Tags** → attributes. A tag written `key:value` (e.g. `NamedTag("team:payments")`) becomes a
  keyed attribute; a plain tag becomes a value-only attribute. Spec-level `@Tags(...)` become `tag`
  attributes on the spec.
- **Severity** (`config(severity = ...)`) → `severity=<LEVEL>` attribute.
- **Invocations** (`config(invocations = N)`) → `invocations=N` attribute (see *Flaky detection*).
- Each **launch** is tagged with `agent` (`kotest-rp|<version>`), `os`, and `jvm` attributes.

## Logs & attachments

Attach logs and files to the currently running test from inside the test body:

```kotlin
test("uploads a screenshot on the login page") {
    ReportPortalLogs.info("navigating to the login page")
    ReportPortalLogs.attach(File("build/screenshots/login.png"), message = "login page")
    // or from raw bytes:
    ReportPortalLogs.attach("payload.json", responseBytes, "application/json")
    ReportPortalLogs.warn("done")
}
```

Available calls: `info` / `warn` / `error` / `debug` / `log(level, message)`, and `attach(...)` for a
`File` or raw bytes. Logs bind to the running test's item. The API is best-effort and non-fatal: if a
custom multi-threaded dispatcher moved execution off the test's thread, the call is a no-op.

## Flaky detection (invocations)

Run a test several times to surface flakiness:

```kotlin
test("sometimes flaky").config(invocations = 5) { /* ... */ }
```

The test gets an `invocations=5` attribute and an `Invocation k of 5` log marker per run, so you can
see how many repetitions completed before a flaky failure.

> Kotest 5.9 doesn't expose per-invocation results to extensions, so these are markers, not
> ReportPortal *retry groups*. Full retry grouping is planned alongside Kotest 6 support.

## Failures & defect types

Failures are marked `FAILED`, get the stacktrace as an `ERROR` log, and are assigned a ReportPortal
defect type. Customize which defect via `RpConfig` (see below) — for example, map assertion failures
to a product bug and exceptions to an automation bug:

```kotlin
RpConfig(
    defectTypeResolver = { _, result ->
        if (result is TestResult.Failure) RpDefect.PRODUCT_BUG else RpDefect.AUTOMATION_BUG
    },
)
```

`RpDefect` provides the built-in locators: `TO_INVESTIGATE` (`ti`), `PRODUCT_BUG` (`pb`),
`AUTOMATION_BUG` (`ab`), `SYSTEM_ISSUE` (`si`), `NO_DEFECT` (`nd`). Any custom project locator string
works too.

## Configuration

Pass an `RpConfig` to the extension:

```kotlin
override fun extensions(): List<Extension> = listOf(
    ReportPortalExtension(
        RpConfig(
            leafItemType = LeafItemType.STEP,
            reportIgnored = true,
            failureDefectType = RpDefect.TO_INVESTIGATE,
        )
    )
)
```

| Option | Default | Description |
|---|---|---|
| `syntheticRootSuite` | `false` | When `true`, nest all specs under one root `SUITE`. |
| `rootSuiteName` | `"Test Suite"` | Name of that root suite (only when the above is `true`). |
| `leafItemType` | `LeafItemType.STEP` | ReportPortal type for leaf tests (`STEP` or `TEST`). |
| `reportIgnored` | `true` | Report ignored/disabled tests as `SKIPPED`. |
| `failureDefectType` | `RpDefect.TO_INVESTIGATE` | Default defect for failures; `null` disables defect typing. |
| `defectTypeResolver` | `null` | `(TestCase, TestResult) -> String?` — overrides `failureDefectType` per failure (`null` = no defect). |
| `parameters` | `null` | Programmatic `ListenerParameters`; when `null`, config is read from properties/env. |

You can also inject a pre-built `ReportPortal` instance:
`ReportPortalExtension(reportPortal, config)`.

## CI: reruns & distributed launches

- **Rerun** — set `rp.rerun=true` (and optionally `rp.rerun.of=<launchUuid>`) to merge a run into the
  history of a previous launch.
- **Shared launch across parallel shards** — two options:
  - **Client join** — set `rp.client.join=true` on every concurrent shard; the ReportPortal client
    elects one launch and the others join it automatically (no code changes).
  - **External launch UUID** — have an orchestrator create the launch, pass its UUID to every shard
    via `rp.launch.uuid=<uuid>`, and let the orchestrator finish it. Each shard reports and flushes
    its items into the shared launch but never creates or closes it.

## Building & testing

```bash
./gradlew build     # compile + run the offline test suite
./gradlew test      # tests only
```

The unit/integration tests run fully **offline** against an in-memory recording ReportPortal client —
no server needed.

There are also opt-in **live** smoke tests (`Live*ReportPortalTest`) that report to a real instance.
They are disabled by default and require a `reportportal.properties` on the test classpath:

```bash
./gradlew test --tests "io.github.qasecret.rp.LiveReportPortalTest" -Drp.live=true
```

> Keep your real `reportportal.properties` out of version control — it holds your API key. This repo
> gitignores it and ships `reportportal.properties.example` as a template.

## License

Apache License 2.0.
