# Contributing to kotest-rp

Thanks for your interest in improving **kotest-rp** — a focused [Kotest](https://kotest.io)
extension that reports test runs to [ReportPortal](https://reportportal.io). Issues and pull
requests are welcome.

By participating, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## Getting started

You need **JDK 17+**. The Gradle wrapper handles everything else.

```bash
./gradlew build     # compile + run the full (offline) test suite
./gradlew test      # tests only
./gradlew compileKotlin   # compile main sources only
```

Run a single test class:

```bash
./gradlew test --tests "io.github.qasecret.rp.RpMapperTest"
```

### Project layout

The public surface is just two classes — `ReportPortalExtension` and `RpConfig`. Internals live under
`io.github.qasecret.rp.internal` (reporter, mapper, logging, agent). See the source for a tour of how
the Kotest lifecycle maps onto ReportPortal items.

## How the tests work

- The suite runs **fully offline** against an in-memory recording ReportPortal client
  (`RecordingReportPortalClient`), so no server is required and tests are deterministic. They exercise
  the **real** `LaunchImpl` lifecycle and assert against recorded requests.
- Sample specs are **nested classes** inside their test classes; the Gradle `test` task excludes
  fixtures nested under `*Test` classes so they don't run directly. Keep new sample specs nested.
- Opt-in **live** smoke tests (`Live*ReportPortalTest`) report to a real instance and need a
  `reportportal.properties` on the test classpath:

  ```bash
  ./gradlew test --tests "io.github.qasecret.rp.LiveReportPortalTest" -Drp.live=true
  ```

Please add or update tests for any behavior change. Bug fixes should come with a regression test.

## Project principles (please preserve)

These are the constraints that keep kotest-rp small and safe — PRs that break them are unlikely to be
merged without discussion:

- **Minimal dependencies.** The published artifact depends only on `com.epam.reportportal:client-java`
  and `org.slf4j:slf4j-api`. It deliberately ships **no logging backend** — do not add logback/log4j2
  (or `logger-java-logback`) to the main `implementation` configuration. Kotest is `compileOnly`.
- **Reporting is non-fatal.** Every ReportPortal interaction is guarded; a failure is logged and
  swallowed so it can never break a consumer's test run. Keep new code non-fatal.
- **Thread/coroutine safe.** Kotest runs on coroutines and may run concurrently. State is held in
  `Atomic*` / `ConcurrentHashMap`, and log attribution rides the test coroutine via SLF4J MDC.

## Compatibility

The `main` branch (kotest-rp **2.x**) targets **Kotest 6** (`6.1.x`) and **Kotlin 2.2+**. Kotest 5
support lives on the **1.2.x** line. Mention which Kotest line a change targets in your PR.

## Submitting a change

1. **Open an issue first** for anything non-trivial, so we can agree on the approach.
2. Fork, create a topic branch, and make your change with tests.
3. Make sure `./gradlew build` passes locally (it runs the full offline suite).
4. Open a pull request against `main`. Fill in the PR template, describe the change and its rationale,
   and link any related issue.
5. CI (`./gradlew build` on JDK 17) must be green before merge.

## Releasing

Releases are tag-driven (maintainers only): pushing a `vX.Y.Z` tag triggers the `Publish` workflow,
which builds, signs, and releases that version to Maven Central and creates a GitHub Release. See the
**Releasing** section in the [README](README.md) for details.

## Reporting security issues

Please do **not** open a public issue for security problems — see [SECURITY.md](SECURITY.md).
