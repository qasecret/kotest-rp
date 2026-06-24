# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`kotest-rp` is a single-purpose Kotlin/JVM library published to Maven Central as
`io.github.qasecret:kotest-rp`. It provides one public class, `ReportPortalExtension`,
that wires the [Kotest](https://kotest.io) test framework into
[ReportPortal](https://reportportal.io) so test runs are reported to a ReportPortal instance.

Consumers register it from their Kotest `AbstractProjectConfig.extensions()`.

## Commands

```bash
./gradlew build            # compile + assemble + run tests
./gradlew test             # run tests (JUnit Platform / Kotest)
./gradlew compileKotlin    # compile main only
./gradlew publishToMavenCentral   # publish a signed release (vanniktech plugin, requires signing creds)
```

Run a single test class:

```bash
./gradlew test --tests "io.github.qasecret.rp.RpMapperTest"
```

## Build / toolchain facts

- Kotlin JVM plugin `2.2.21`, `jvmToolchain(17)`. (Kotlin must be ≥ 2.2 because Kotest 6.1.4 is
  compiled with Kotlin 2.2 metadata; an older compiler fails with an "incompatible metadata version".)
- Targets **Kotest 6.1.4** (`kotestVersion` in [gradle.properties](gradle.properties)). Kotest 6 folded
  the former `kotest-framework-api` module into `kotest-framework-engine`, and `withData` (datatest)
  now ships inside the engine too — so the build depends on `kotest-framework-engine-jvm` only (no
  separate `-api` / `-datatest` artifacts).
- Library `version` and Maven coordinates are set directly in [build.gradle.kts](build.gradle.kts),
  not in `gradle.properties`. The only `by project` value is `kotestVersion` (in
  [gradle.properties](gradle.properties)); everything else is hardcoded in the build script.
- **Dependency policy:** the published artifact depends only on `client-java` (ReportPortal) and
  `slf4j-api`. It deliberately ships **no logging backend** (consumers pick their own). Kotest is
  `compileOnly` (provided by the consumer, who is by definition using Kotest). Tests add the Kotest
  engine/runner/assertions, `okhttp` (only to implement the recording client's `log` member), and
  `logback-classic` as `testRuntimeOnly`. Do not reintroduce logback/testng/`logger-java-logback`
  to the main `implementation` config.
- CI: [.github/workflows/build.yml](.github/workflows/build.yml) runs `./gradlew build` on push/PR
  (JDK 17). [.github/workflows/publish.yml](.github/workflows/publish.yml) publishes on GitHub release
  (JDK 21).

## Configuration (runtime)

The no-arg `ReportPortalExtension()` reads the standard ReportPortal configuration via
`ReportPortal.builder().build()` (`reportportal.properties` on the classpath and/or `rp.*` env vars /
system properties: `rp.endpoint`, `rp.api.key`, `rp.project`, `rp.launch`, `rp.enable`, …).
Reporting behavior is customized via [RpConfig](src/main/kotlin/io/github/qasecret/rp/RpConfig.kt)
(passed to a constructor) — e.g. `syntheticRootSuite`, `leafItemType` (STEP/TEST), `reportIgnored`,
`failureDefectType`, or a programmatic `ListenerParameters`. A `ReportPortal` instance can also be
injected directly (this is the seam the tests use with a recording client).

## Architecture

The code is decomposed into four cohesive units (public surface = `ReportPortalExtension` + `RpConfig`):

- [ReportPortalExtension.kt](src/main/kotlin/io/github/qasecret/rp/ReportPortalExtension.kt) — thin
  facade implementing the granular Kotest listener interfaces (`ProjectListener`,
  `PrepareSpecListener`, `BeforeTestListener`, `AfterTestListener`, `FinalizeSpecListener`,
  `BeforeInvocationListener`, `IgnoredTestListener`, `TestCaseExtension`). Each callback is wrapped in
  `guard { }` so reporting failures never affect the test run, and delegates to `RpReporter`.
- [internal/RpReporter.kt](src/main/kotlin/io/github/qasecret/rp/internal/RpReporter.kt) — owns the
  `Launch` and the item tree; performs all ReportPortal calls.
- [internal/RpMapper.kt](src/main/kotlin/io/github/qasecret/rp/internal/RpMapper.kt) — **pure**
  functions translating Kotest model objects → ReportPortal values (keys, names, item types, status,
  codeRef, attributes). Unit-tested in isolation. Attribute mapping: test tags → attributes
  (`key:value` tags become keyed; plain tags value-only), `config.severity` → `severity=<LEVEL>`,
  `config.invocations > 1` → `invocations=N`, spec `@Tags` → `tag` attributes. **Kotest 6 nullability:**
  `TestCase.config` is a sparse `TestConfig?` whose fields (`tags`, `severity`, `invocations`) are
  themselves nullable; the mapper falls back to `emptySet()` / `TestCaseSeverityLevel.NORMAL` / `1`.
- **Flaky/invocations** (`BeforeInvocationListener.beforeInvocation(testCase, iteration)` →
  `RpReporter.logInvocation`): multi-invocation tests get a `Invocation k of N` log marker per run.
  Kotest exposes no per-attempt result here, so true RP retry *groups* aren't built — only count +
  markers.
- [internal/RpAgent.kt](src/main/kotlin/io/github/qasecret/rp/internal/RpAgent.kt) — agent identity;
  adds `agent`/`os`/`jvm` launch attributes. Version is baked into `src/main/resources/kotest-rp.properties`
  at build time (Gradle `processResources` expands `${version}`). NB: client-supplied `system=true`
  attributes are dropped by the RP server on launch creation, so these are sent as regular attributes.
- [RpConfig.kt](src/main/kotlin/io/github/qasecret/rp/RpConfig.kt) — config data class + `LeafItemType`
  + `RpDefect` locator constants. Defect typing: `defectTypeResolver` (a `(TestCase, TestResult) -> String?`
  lambda) wins when set (null return = no defect); otherwise `failureDefectType` is the default. Applied
  in `RpReporter.finishTest` via `defectTypeFor(...)`.
- [ReportPortalLogs.kt](src/main/kotlin/io/github/qasecret/rp/ReportPortalLogs.kt) (public) +
  [internal/RpLog.kt](src/main/kotlin/io/github/qasecret/rp/internal/RpLog.kt) — attach logs/files to
  the running test's item from inside a test body. Text logs post directly via `client.log(SaveLogRQ)`
  with explicit item/launch UUIDs (deterministic, offline-testable); **file attachments** must use the
  multipart path, so they go through a per-call `LoggingContext` on the test-body thread.
- **Concurrency-safe log attribution** (the key resolution that makes both `ReportPortalLogs` *and*
  ordinary SLF4J/logback logging land on the right item): `ReportPortalExtension` implements
  `TestCaseExtension` and its `intercept` wraps each test in [internal/RpItemContextElement.kt] — a
  `ThreadContextElement` that publishes the test's item key (`RpMapper.testKey`) to SLF4J **MDC**
  ([RpMdc.ITEM_KEY], public). Because it rides the test coroutine, the key stays correct across
  coroutine **thread-hops** (`withContext(Dispatchers.IO)`) and **concurrent** tests, which a plain
  `ThreadLocal` cannot. `RpLog` resolves the target item solely by that MDC key via a
  `ConcurrentHashMap` registry (`register` when the item is created, `unregister` in `finishTest`,
  `clear` in `finishLaunch`); logs with no MDC key (outside a test coroutine) are a no-op. For a
  **deferred** container whose item doesn't exist yet, `RpReporter` calls `RpLog.beginBuffering(key)`
  and logs are queued, then flushed (original timestamps preserved) when `register` runs at item
  creation. Verified by `ConcurrentLoggingTest` and `WithDataReportingTest`.
- [logback/ReportPortalLogbackAppender.kt](src/main/kotlin/io/github/qasecret/rp/logback/ReportPortalLogbackAppender.kt)
  (public, **`compileOnly` logback**) — turnkey appender so consumers' normal `LoggerFactory.getLogger`
  logs flow to RP with zero custom code; it just forwards each event to `ReportPortalLogs.log` (which
  resolves via the MDC key). Logback stays `compileOnly` so the no-logging-backend dependency policy
  holds; non-logback users write a tiny appender against `RpMdc.ITEM_KEY` instead. **Empirically:** the
  official `logger-java-logback` appender also works for *sequential* runs, but misattributes under
  concurrency — this bundled path is the reliable one.

ReportPortal hierarchy produced (default; with `syntheticRootSuite=true` a root SUITE is inserted
between Launch and the specs):

```
Launch                              (beforeProject)
 └─ <Spec class>      (SUITE)       (prepareSpec)
     └─ container     (SUITE)       (beforeTest, TestType.Container that gets children)
         └─ test/leaf (STEP|TEST)   (beforeTest, TestType.Test, or a childless Container)
```

Lifecycle mapping (each guarded so it runs once where relevant):
- `beforeProject` → `startLaunch`: checks `rp.parameters.enable`; on disabled/failure logs **one**
  warning and short-circuits all later calls (non-fatal). Starts the launch (+ optional root SUITE).
- `prepareSpec(kclass)` → `startSpec`: SUITE per spec class. Uses `prepareSpec`/`finalizeSpec` (not
  `beforeSpec`/`afterSpec`) because they fire **exactly once per class regardless of `IsolationMode`**.
- `beforeTest(testCase)` → `startTest`: leaf (`TestType.Test`)→`config.leafItemType` created eagerly;
  `TestType.Container` is **deferred** (parked in `pending`, see below). Parent resolved via
  `RpMapper.parentKey` → item map → spec → root.
- `afterTest(testCase, result)` → `finishTest`: status; on failure sets `Issue(failureDefectType)`,
  a concise description, and an ERROR log with the stacktrace **bound to the item UUID**.
- `ignoredTest(testCase, reason)` → `RpReporter.reportIgnored`: emits a SKIPPED item for a
  disabled/ignored test. **Kotest 6 delivers ignored tests only through `IgnoredTestListener`** — they
  no longer appear in `finalizeSpec`'s results map (now empty) and never fire `beforeTest`/`afterTest`.
  **Known limitation:** `ignoredTest` fires per-test only when the spec has ≥1 *enabled* test. A spec
  whose tests are **all** disabled is skipped wholesale at the spec level (verified: `prepareSpec` +
  `finalizeSpec` with an empty results map still fire, so the **spec** is reported as a SKIPPED SUITE),
  but its individual disabled tests are not itemized — the engine exposes no per-test signal for them
  to any extension listener. This is unfixable at the extension layer; the spec-level SKIPPED suite is
  the best fidelity available.
- `finalizeSpec(kclass, results)` → `finishSpec`: finishes any deferred containers (`finishDeferred`),
  then finishes the spec SUITE with a rolled-up status.
- `afterProject` → `finishLaunch`: finishes root suite (if any) + launch; clears state.

Key implementation details to preserve when editing:
- **`TestType.Container` nodes are materialized lazily.** Kotest 6 has no `TestType.Dynamic`; it types
  every node eagerly as `Container` or `Test`. But `withData` (datatest) makes **every** data row a
  `TestType.Container` — *including* a row whose block only asserts (no nested tests), which is really a
  leaf. We can't tell a real suite from a childless `withData` leaf at `beforeTest`, so `startTest` does
  **not** create an RP item for a Container immediately — it parks it in `pending`. `resolveParent`
  materializes a pending parent as a **SUITE** the moment a child appears under it. A container still
  pending at its own `finishTest` is **not** fixed as a leaf there — its finish is *deferred*
  (`deferredFinish`) to `finishSpec`, because it may still own **ignored** children (reported eagerly via
  `ignoredTest`→`reportSkipped`) that must be able to promote it to a SUITE first. `finishDeferred` then
  materializes whatever is *still* only in `pending` as a **leaf**; the rest were already promoted to
  SUITEs. This is load-bearing: a childless `withData` row created eagerly as a SUITE would be an empty
  suite contributing no pass/fail counts; one with children created eagerly as a leaf would make RP nest
  the real Given/When/Then items as *nested steps* under a STEP, so they never roll up into launch
  statistics. **Invariant: nothing may be parented under a STEP/TEST item.** Verified by
  `WithDataReportingTest` (nested-container, all-ignored, leaf, and leaf-body-logging cases). Because
  deferral applies to **every** container (Kotest 6 can't distinguish a `withData` row from a normal
  `context`/`Given`), two consequences follow: (1) a genuinely empty / fully tag-filtered container
  (no executed and no ignored children) is materialized as a **leaf** at `finishDeferred` rather than an
  empty SUITE — acceptable, and better for stats than an empty suite; (2) logs emitted directly in a
  container/leaf body run *before* its RP item exists, so they are **buffered** by `RpLog`
  (`beginBuffering` on defer) and **flushed with their original timestamps** when the item is
  materialized — not dropped. Failure ERROR logs are unaffected (sent when the item is finished).
- **Item keys are hashCode-free and collision-free.** Specs: `"spec:<fqcn>"`; tests:
  `"test:<descriptor.path().value>"` (the full, globally-unique path; Kotest 6 dropped `path`'s
  affix-toggle argument, so `codeRef` shares the same value). Parent resolution walks
  `Descriptor.TestDescriptor.parent` / `SpecDescriptor`. Changing key formats breaks parent linking.
- **Logging never relies on ReportPortal's ambient `LoggingContext`** for text logs. RP's
  `LoggingContext` is a thread-local `Deque` that is unreliable across Kotest's coroutine dispatch.
  Failure logs (in `finishTest`) and `ReportPortalLogs` text logs are sent via `rp.client.log(SaveLogRQ)`
  with explicit item + launch UUIDs (resolved with `Maybe.blockingGet`). The "current item" is resolved
  through **our own** MDC key channel ([RpMdc.ITEM_KEY] + `RpItemContextElement`, see above), not RP's
  `LoggingContext`. Do not route text logging through RP's ambient `LoggingContext`. (File attachments
  still need a per-call `LoggingContext` because the multipart upload path requires it.)
- **Timestamps use full `Date()` precision** (millisecond). The model time fields are `java.util.Date`
  in client-java 5.2.23 (not `Instant`). Do not truncate to seconds.
- **Non-fatal everywhere.** `RpReporter` catches per-operation and `ReportPortalExtension.guard`
  is a final safety net. State is `Atomic*`/`ConcurrentHashMap` for thread safety.
- **Distributed/rerun launches** (`RpReporter.startLaunch`/`finishLaunch`): rerun params
  (`rp.rerun`/`rp.rerun.of`) propagate onto `StartLaunchRQ`. For an external `rp.launch.uuid`, the
  reporter sets `launchUuidCreationSkip` and **still calls `launch.finish()`** — crucial because
  `LaunchImpl.finish()` is what *flushes* pending item requests; it skips the actual finish-launch
  server call when creation-skip is set, so the shared launch stays open for its owner. (Do NOT skip
  `finish()` for external launches — that loses the flush and leaves items INTERRUPTED. Verified via
  the two-shard live test.) The synthetic root suite is skipped when attaching externally.

## Tests

- [RecordingReportPortalClient](src/test/kotlin/io/github/qasecret/rp/support/RecordingReportPortalClient.kt)
  is a fake `ReportPortalClient` wired via `ReportPortal.create(client, params)`, so tests exercise
  the **real** `LaunchImpl` lifecycle and assert against recorded requests.
- [ReportPortalExtensionEngineTest](src/test/kotlin/io/github/qasecret/rp/ReportPortalExtensionEngineTest.kt)
  drives sample specs through `io.kotest.engine.TestEngineLauncher` and asserts the launch/item tree
  (types, parent links, PASSED/FAILED/SKIPPED, failure issue + log, once-per-spec under InstancePerLeaf).
  The launcher's `launch()` terminal and `withClasses(...)` were removed in Kotest 6 — a test-only shim
  ([support/TestEngineLauncherCompat.kt]) restores them (`execute()` via `runBlocking`,
  `withSpecRefs(SpecRef.Reference(..))`). `TestEngineLauncher` is `@KotestInternal`, opted into for the
  test compilation in [build.gradle.kts](build.gradle.kts).
- Sample specs are **nested classes** inside the test classes. Unlike Kotest 5, Kotest 6's JUnit-platform
  discovery scans for **every** concrete `Spec` subclass regardless of nesting, so the test task
  excludes `$`-containing class names (`filter { excludeTestsMatching("*\$*") }` in
  [build.gradle.kts](build.gradle.kts)) to stop those fixtures running directly. The in-test
  `TestEngineLauncher` is a separate engine instance, unaffected by that filter, so it still launches
  them explicitly. (Do NOT use `@Ignored` to exclude them — it's enforced in the engine's spec-execution
  path and would also skip the explicit launches.)
- `TestResult` lives in `io.kotest.engine.test` (was `io.kotest.core.test` in Kotest 5).
- [RpMapperTest](src/test/kotlin/io/github/qasecret/rp/RpMapperTest.kt) covers the pure mapper.
