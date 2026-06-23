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

- Kotlin JVM plugin `2.0.0`, `jvmToolchain(17)`.
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
  `PrepareSpecListener`, `BeforeTestListener`, `AfterTestListener`, `FinalizeSpecListener`). Each
  callback is wrapped in `guard { }` so reporting failures never affect the test run, and delegates
  to `RpReporter`.
- [internal/RpReporter.kt](src/main/kotlin/io/github/qasecret/rp/internal/RpReporter.kt) — owns the
  `Launch` and the item tree; performs all ReportPortal calls.
- [internal/RpMapper.kt](src/main/kotlin/io/github/qasecret/rp/internal/RpMapper.kt) — **pure**
  functions translating Kotest model objects → ReportPortal values (keys, names, item types, status,
  codeRef, attributes). Unit-tested in isolation. Attribute mapping: test tags → attributes
  (`key:value` tags become keyed; plain tags value-only), `config.severity` → `severity=<LEVEL>`,
  `config.invocations > 1` → `invocations=N`, spec `@Tags` → `tag` attributes.
- **Flaky/invocations** (`BeforeInvocationListener` → `RpReporter.logInvocation`): multi-invocation
  tests get a `Invocation k of N` log marker per run. Kotest 5.9's `afterInvocation` carries **no
  per-attempt result** and `retries` isn't in `ResolvedTestConfig` until Kotest 6, so true RP retry
  *groups* aren't built — only count + markers. Revisit for real retry grouping on Kotest 6.
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
  `ConcurrentHashMap` registry (`register` in `startTest`, `unregister` in `finishTest`,
  `clear` in `finishLaunch`); logs with no MDC key (outside a test coroutine) are a no-op. Verified by
  `ConcurrentLoggingTest`.
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
     └─ container     (SUITE)       (beforeTest, TestType.Container, or a Dynamic node with children)
         └─ test/leaf (STEP|TEST)   (beforeTest, TestType.Test, or a childless Dynamic node)
```

Lifecycle mapping (each guarded so it runs once where relevant):
- `beforeProject` → `startLaunch`: checks `rp.parameters.enable`; on disabled/failure logs **one**
  warning and short-circuits all later calls (non-fatal). Starts the launch (+ optional root SUITE).
- `prepareSpec(kclass)` → `startSpec`: SUITE per spec class. Uses `prepareSpec`/`finalizeSpec` (not
  `beforeSpec`/`afterSpec`) because they fire **exactly once per class regardless of `IsolationMode`**.
- `beforeTest(testCase)` → `startTest`: Container→`SUITE`, leaf→`config.leafItemType`; parent resolved
  via `RpMapper.parentKey` → item map → spec → root.
- `afterTest(testCase, result)` → `finishTest`: status; on failure sets `Issue(failureDefectType)`,
  a concise description, and an ERROR log with the stacktrace **bound to the item UUID**.
- `finalizeSpec(kclass, results)` → `finishSpec`: emits SKIPPED items for ignored tests (which never
  get `beforeTest`), then finishes the spec SUITE with a rolled-up status.
- `afterProject` → `finishLaunch`: finishes root suite (if any) + launch; clears state.

Key implementation details to preserve when editing:
- **`withData`/`TestType.Dynamic` nodes are materialized lazily.** A datatest data node is
  `TestType.Dynamic`, which Kotest only resolves to leaf-or-container once its block runs (a leaf when
  the block asserts directly; a container when it opens nested `Given`/`When`/`Then`/etc). `startTest`
  therefore does **not** create an RP item for a Dynamic node immediately — it parks it in `pending`.
  `resolveParent` materializes a pending parent as a **SUITE** the moment a child appears under it. A
  Dynamic node still pending at its own `finishTest` is **not** fixed as a leaf there — its finish is
  *deferred* (`deferredFinish`) to `finishSpec`, because it may still own **ignored** children that only
  `finishSpec` reports (via `reportSkipped`), and those must be able to promote it to a SUITE first.
  `finishDeferred` runs **after** the ignored-test loop: whatever is still only in `pending` by then
  genuinely had no children and is materialized as a **leaf**; the rest were already promoted to SUITEs.
  This is load-bearing: creating Dynamic nodes eagerly as leaves (the original behavior) made RP nest the
  real Given/When/Then items as *nested steps* under a STEP, so they never rolled up into launch
  statistics (no pass/fail counts, broken tree); fixing a node as a leaf at `finishTest` re-broke the
  same way for the all-children-ignored case. **Invariant: nothing may be parented under a STEP/TEST
  item.** Verified by `WithDataReportingTest` (nested-container, all-ignored, and leaf cases). Trade-off:
  logs emitted *directly in a `withData` leaf body* (not in a nested `Then`) attach when the leaf
  finishes rather than mid-body, since the item doesn't exist until then; failure ERROR logs are
  unaffected (always sent when the item is finished).
- **Item keys are hashCode-free and collision-free.** Specs: `"spec:<fqcn>"`; tests:
  `"test:<descriptor.path(true).value>"` (the full, globally-unique path). Parent resolution walks
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
- Sample specs are **nested classes** inside the test classes so Kotest's classpath discovery does
  not run them in the outer build — keep them nested.
- [RpMapperTest](src/test/kotlin/io/github/qasecret/rp/RpMapperTest.kt) covers the pure mapper.
