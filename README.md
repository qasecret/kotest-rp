<div align="center">

# kotest-rp

**Report your [Kotest](https://kotest.io) runs to [ReportPortal](https://reportportal.io) — one line of setup, a faithful test tree, and your existing logs flowing straight into each test.**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.qasecret/kotest-rp?style=flat-square&logo=apachemaven&color=blue)](https://central.sonatype.com/artifact/io.github.qasecret/kotest-rp)
[![Build](https://img.shields.io/github/actions/workflow/status/qasecret/kotest-rp/build.yml?branch=main&style=flat-square&logo=github)](https://github.com/qasecret/kotest-rp/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-green?style=flat-square)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-JVM-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Kotest](https://img.shields.io/badge/Kotest-6.1.x-4DB33D?style=flat-square)](https://kotest.io)

</div>

---

`kotest-rp` is a tiny, focused Kotest extension. Register it once and your **launches, specs, tests,
statuses, attributes, failures, logs, and attachments** appear in ReportPortal with a structure that
mirrors your specs exactly — at any nesting depth, for every Kotest spec style.

```kotlin
class ProjectConfig : AbstractProjectConfig() {
    override val extensions = listOf(ReportPortalExtension())
}
```

That's the whole integration. Connection settings come from a `reportportal.properties` file or
`rp.*` env vars — no code changes to wire up CI, and reporting is **non-fatal**, so a flaky
ReportPortal server can never break your build.

> **Kotest 6 / Kotest 5?** `kotest-rp` **2.x** targets **Kotest 6** (`6.1.x`, Kotlin 2.2+) — and
> registration is `override val extensions` (a property, not a function). Still on **Kotest 5**? Use
> `kotest-rp` **1.2.x** with `override fun extensions()`. See [Compatibility](#compatibility).

## Table of contents

- [Why kotest-rp](#why-kotest-rp)
- [Install](#install)
- [Quick start](#quick-start)
- [What it reports](#what-it-reports)
- [Logging: get your existing logs into ReportPortal](#logging-get-your-existing-logs-into-reportportal)
- [Attributes & metadata](#attributes--metadata)
- [Failures & defect types](#failures--defect-types)
- [Configuration reference](#configuration-reference)
- [CI: reruns & distributed launches](#ci-reruns--distributed-launches)
- [Troubleshooting](#troubleshooting)
- [Compatibility](#compatibility)
- [Contributing & building](#contributing--building)
- [License](#license)

## Why kotest-rp

| | |
|---|---|
| 🌳 **Faithful tree** | Specs and containers become `SUITE`s, leaf tests become `STEP`s (or `TEST`s) — at any depth, for Fun/String/Should/Describe/Behavior/Word/Feature/Expect/Free/Annotation specs, with their natural `Given:`/`Describe:`/`Feature:` affixes preserved. |
| 🪵 **Logs that just work** | Drop in the bundled logback appender and your ordinary `log.info(...)` calls attach to the right test — **correctly even under concurrent tests and coroutine thread‑hops**. No `ReportPortal`‑specific code in your tests. |
| 🎯 **Accurate statuses** | Passed / failed / skipped; ignored & disabled tests are reported too. Failures carry the stacktrace and a configurable defect type. |
| 🏷️ **Rich metadata** | Kotest tags (incl. `key:value`), severity, invocation counts, and spec `@Tags` → ReportPortal attributes. |
| 📎 **Attachments** | Send screenshots, payloads, and files to the running test from inside the test body. |
| 🧩 **CI‑ready** | Rerun mode and distributed launches (client‑join or external launch UUID) for sharded pipelines. |
| 🪶 **Lightweight & safe** | Depends only on the ReportPortal client + `slf4j-api` — **no logging backend forced on you**. Every reporting call is guarded; failures are logged and swallowed. |

## Install

`kotest-rp` is a **test-time** dependency. Use the latest version from the badge above. Version **2.x**
requires **Kotest 6** (`6.1.x`) and **Kotlin 2.2+**.

<details open>
<summary><b>Gradle (Kotlin DSL)</b></summary>

```kotlin
val kotestVersion = "6.1.4"

dependencies {
    testImplementation("io.github.qasecret:kotest-rp:<version>")

    // Kotest is `compileOnly` in kotest-rp, so the consumer provides it. The runner pulls in the
    // engine (which in Kotest 6 also contains the `withData` datatest helpers — no separate
    // `kotest-framework-datatest` artifact anymore); add assertions if you use the matchers.
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")

    // Pick any SLF4J backend (kotest-rp ships none):
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.6")
}
```
</details>

<details>
<summary><b>Gradle (Groovy DSL)</b></summary>

```groovy
def kotestVersion = '6.1.4'

dependencies {
    testImplementation 'io.github.qasecret:kotest-rp:<version>'
    testImplementation "io.kotest:kotest-runner-junit5:$kotestVersion"
    testImplementation "io.kotest:kotest-assertions-core:$kotestVersion"
    testRuntimeOnly 'ch.qos.logback:logback-classic:1.5.6'
}
```
</details>

<details>
<summary><b>Maven</b></summary>

```xml
<dependency>
  <groupId>io.github.qasecret</groupId>
  <artifactId>kotest-rp</artifactId>
  <version>VERSION</version>
  <scope>test</scope>
</dependency>
```
</details>

The published artifact pulls in only `com.epam.reportportal:client-java` and `org.slf4j:slf4j-api` —
you choose your own SLF4J backend.

## Quick start

### 1. Register the extension

```kotlin
import io.github.qasecret.rp.ReportPortalExtension
import io.kotest.core.config.AbstractProjectConfig

class ProjectConfig : AbstractProjectConfig() {
    override val extensions = listOf(ReportPortalExtension())
}
```

> In Kotest 6 `extensions` is a **property** (`override val extensions = …`). On Kotest 5 (kotest-rp
> 1.2.x) it's the function form `override fun extensions() = …`.

### 2. Provide connection settings

The no-arg `ReportPortalExtension()` reads the standard ReportPortal configuration — a
`reportportal.properties` on the test classpath (`src/test/resources/`) and/or `rp.*` system
properties / environment variables:

```properties
rp.endpoint = https://your-reportportal-instance
rp.api.key  = your-api-key
rp.project  = your-project
rp.launch   = Kotest Launch
# rp.enable = false   # turn reporting off without removing the extension
```

> 🔒 Keep real credentials out of version control. Commit a `reportportal.properties.example` and
> inject the real `rp.api.key` via an env var / CI secret.

### 3. Run your tests

```bash
./gradlew test
```

A launch appears in ReportPortal with your full spec tree. Done.

## What it reports

```
Launch                                   (beforeProject)
 └─ com.acme.LoginSpec        (SUITE)     ← one per spec class
     ├─ "logs in"             (STEP)      ← leaf test
     └─ Describe: validation  (SUITE)     ← nested container
         └─ "rejects empty"   (STEP)
```

| Kotest concept | ReportPortal item |
|---|---|
| Spec class | `SUITE` (directly under the launch, or under a synthetic root — see `syntheticRootSuite`) |
| Container (`context`/`describe`/`given`/`when`/`feature`/…) | `SUITE` |
| Leaf test | `STEP` (or `TEST` via `leafItemType`) |
| Ignored / disabled test | `SKIPPED` |
| Failed test | `FAILED` + stacktrace as an `ERROR` log + a defect type |

Every **launch** is also tagged with `agent` (`kotest-rp|<version>`), `os`, and `jvm` attributes.

## Logging: get your existing logs into ReportPortal

There are two complementary ways to attach logs to a test. **Most projects only need the first.**

### A. Automatic — your normal SLF4J/logback logs (recommended)

> _Requires kotest-rp **≥ 1.2.0**._

Register the bundled appender in `src/test/resources/logback-test.xml`:

```xml
<configuration>
  <appender name="REPORTPORTAL" class="io.github.qasecret.rp.logback.ReportPortalLogbackAppender"/>

  <root level="INFO">
    <appender-ref ref="REPORTPORTAL"/>
    <!-- keep your console appender too, if you like -->
  </root>
</configuration>
```

Now your everyday logging flows straight into the matching ReportPortal test — **no changes to your
test code**:

```kotlin
private val log = LoggerFactory.getLogger(MySpec::class.java)

class MySpec : BehaviorSpec({
    Given("a user") {
        log.info("seeding the database")          // attaches to the "Given" suite
        When("they log in") {
            Then("a token is issued") {
                log.info("calling /auth")          // attaches to this test step
                log.error("upstream timed out", ex) // ERROR + full stacktrace
            }
        }
    }
})
```

Attribution is **concurrency- and thread-hop-safe**: each test's item key rides its coroutine via
SLF4J `MDC`, so logs land on the right test even under concurrent execution
(`TestExecutionMode.Concurrent` in Kotest 6) or a `withContext(Dispatchers.IO)` thread hop.
(`DEBUG` lines appear only if your root/logger level allows them.)

> **Using log4j2 or another backend?** kotest-rp publishes the current test's item key to SLF4J `MDC`
> under [`RpMdc.ITEM_KEY`](src/main/kotlin/io/github/qasecret/rp/RpMdc.kt). Write a small appender that
> forwards each event to `ReportPortalLogs.log(level, message)` (it reads the same key), or read the
> MDC value yourself.

### B. Explicit API — logs & file attachments from a test body

Use [`ReportPortalLogs`](src/main/kotlin/io/github/qasecret/rp/ReportPortalLogs.kt) when you want to
attach **files** (screenshots, payloads) or log explicitly:

```kotlin
test("uploads a screenshot on the login page") {
    ReportPortalLogs.info("navigating to the login page")
    ReportPortalLogs.attach(File("build/screenshots/login.png"), message = "login page")
    ReportPortalLogs.attach("payload.json", responseBytes, "application/json")   // raw bytes
    ReportPortalLogs.warn("done")
}
```

Calls: `info` / `warn` / `error` / `debug` / `log(level, message)`, plus `attach(...)` for a `File` or
raw bytes. Like the appender, these resolve to the running test via the MDC item key (so they work
under concurrency); logging entirely outside a running test is a safe no-op.

## Attributes & metadata

- **Tags → attributes.** `NamedTag("team:payments")` (a `key:value` tag) becomes a keyed attribute;
  a plain tag becomes value-only. Spec-level `@Tags(...)` become `tag` attributes on the spec.
- **Severity** — `.config(severity = TestCaseSeverityLevel.CRITICAL)` → `severity=CRITICAL`.
- **Invocations** — `.config(invocations = 5)` → an `invocations=5` attribute and an `Invocation k of 5`
  marker per run, so you can see how many repetitions completed before a flaky failure.

```kotlin
Then("checkout succeeds")
    .config(
        tags = setOf(NamedTag("team:payments"), NamedTag("smoke")),
        severity = TestCaseSeverityLevel.CRITICAL,
        invocations = 3,
    ) { /* ... */ }
```

> Kotest doesn't expose per-invocation results to extensions, so invocations produce **markers**,
> not ReportPortal *retry groups*.

## Failures & defect types

Failures are marked `FAILED`, get the stacktrace as an `ERROR` log, and are assigned a ReportPortal
defect type (**To Investigate** by default). Customize per failure — e.g. assertion failures →
*Product Bug*, exceptions → *Automation Bug*:

```kotlin
import io.kotest.engine.test.TestResult   // Kotest 6 (was io.kotest.core.test.TestResult in Kotest 5)

RpConfig(
    defectTypeResolver = { _, result ->
        if (result is TestResult.Failure) RpDefect.PRODUCT_BUG else RpDefect.AUTOMATION_BUG
    },
)
```

`RpDefect` provides the built-in locators: `TO_INVESTIGATE` (`ti`), `PRODUCT_BUG` (`pb`),
`AUTOMATION_BUG` (`ab`), `SYSTEM_ISSUE` (`si`), `NO_DEFECT` (`nd`). Any custom project locator string
works too.

> ReportPortal renders defects on **STEP** leaves. If you set `leafItemType = TEST`, defect badges
> won't appear on those leaves — keep `STEP` (the default) when you rely on defect typing.

## Configuration reference

Pass an [`RpConfig`](src/main/kotlin/io/github/qasecret/rp/RpConfig.kt) to the extension:

```kotlin
override val extensions = listOf(
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

You can also inject a pre-built `ReportPortal` instance: `ReportPortalExtension(reportPortal, config)`.

## CI: reruns & distributed launches

- **Rerun** — set `rp.rerun=true` (and optionally `rp.rerun.of=<launchUuid>`) to merge a run into the
  history of a previous launch.
- **Shared launch across parallel shards** — two options:
  - **Client join** — set `rp.client.join=true` on every concurrent shard; the ReportPortal client
    elects one launch and the others join it automatically (no code changes).
  - **External launch UUID** — have an orchestrator create the launch, pass its UUID to every shard via
    `rp.launch.uuid=<uuid>`, and let the orchestrator finish it. Each shard reports and flushes its
    items into the shared launch but never creates or closes it.

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| **No launch appears** | Check `rp.endpoint`, `rp.api.key`, `rp.project`; ensure `rp.enable` isn't `false`. The extension logs **one** warning and disables itself if it can't start — look for it. |
| **`ProjectConfig` ignored** | The class must extend `AbstractProjectConfig` and be discoverable on the test classpath (Kotest auto-detects a single one). |
| **Logs not showing** | Register the `REPORTPORTAL` appender (Section A) **and** make sure the message's level passes your root/logger level (`DEBUG` needs `level="DEBUG"`). |
| **`DEBUG` lines missing** | Expected — raise the root or a specific logger to `DEBUG` in your logback config. |
| **Defect badge missing on a leaf** | You set `leafItemType = TEST`; defects render on `STEP` leaves only. |
| **Container (`Given`/`When`) logs not where you look** | They attach to the **suite** node, not the leaf — open that suite row to see them (this is the faithful-tree trade-off). |
| **A spec where *every* test is disabled shows no individual `SKIPPED` items** | Kotest 6 skips such a spec wholesale at the spec level, so the **spec** is reported as a `SKIPPED` suite but its tests aren't itemized (the engine exposes no per-test signal for them). A spec with at least one enabled test reports its disabled tests normally. |

## Compatibility

| | |
|---|---|
| **kotest-rp 2.x** | **Kotest 6** (`6.1.x`), **Kotlin 2.2+** |
| **kotest-rp 1.2.x** | Kotest 5 (`5.9.x`) |
| **JVM** | 17+ |
| **ReportPortal** | client-java 5.2.x (server v5) |

> **Upgrading from kotest-rp 1.x (Kotest 5 → 6)?** Bump `kotest-rp` to `2.x` and Kotest to `6.1.x`
> (Kotlin 2.2+), drop any `kotest-framework-datatest` dependency (it's now part of
> `kotest-framework-engine`), and change extension registration from `override fun extensions() = …`
> to `override val extensions = …`. If you use `defectTypeResolver`/`TestResult`, update the import to
> `io.kotest.engine.test.TestResult`. No other source changes are needed — the reporting behavior,
> config, and logging API are unchanged.

## Contributing & building

```bash
./gradlew build     # compile + run the offline test suite
./gradlew test      # tests only
```

The unit/integration tests run fully **offline** against an in-memory recording ReportPortal client —
no server needed. Opt-in **live** smoke tests (`Live*ReportPortalTest`) report to a real instance and
require a `reportportal.properties` on the test classpath:

```bash
./gradlew test --tests "io.github.qasecret.rp.LiveReportPortalTest" -Drp.live=true
```

Issues and PRs are welcome. Please keep the dependency footprint minimal (client-java + slf4j-api
only) and reporting non-fatal. Release notes live in [CHANGELOG.md](CHANGELOG.md).

<details>
<summary><b>Releasing (maintainers)</b></summary>

Publishing to Maven Central is **automatic and tag-driven**:

1. Push a version tag:
   ```bash
   git tag v2.0.0 && git push origin v2.0.0
   ```
2. The `Publish` workflow builds, signs, uploads, **and releases** that version
   (`publishAndReleaseToMavenCentral`). The version comes from the tag (`v2.0.0` → `2.0.0`); local and
   non-tag builds use a `-SNAPSHOT` version. You can also trigger it manually (Actions → *Publish* →
   *Run workflow*) with an explicit version.

Requires repository secrets: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY_ID`,
`SIGNING_PASSWORD`, `GPG_KEY_CONTENTS`.
</details>

## License

[Apache License 2.0](LICENSE).
