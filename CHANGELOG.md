# Changelog

All notable changes to `kotest-rp` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Releases are published to
Maven Central as `io.github.qasecret:kotest-rp` and are tag-driven (`vX.Y.Z`).

## [Unreleased]

## [1.2.0] - Unreleased

### Added

- **Bundled logback appender** `io.github.qasecret.rp.logback.ReportPortalLogbackAppender`. Register it
  in `logback.xml` / `logback-test.xml` and your ordinary SLF4J/logback logs are forwarded to the
  ReportPortal item for the running test — no ReportPortal-specific calls in your test code. (logback
  stays a `compileOnly` dependency, so the "no logging backend shipped" policy is unchanged.)
- **Concurrency- and thread-hop-safe log attribution.** `ReportPortalExtension` now implements Kotest's
  `TestCaseExtension` and publishes the current test's item key to SLF4J `MDC` (public
  `io.github.qasecret.rp.RpMdc.ITEM_KEY`) via a coroutine `ThreadContextElement`. Logs now attach to
  the correct test even with `concurrency > 1` or after `withContext(Dispatchers.IO)`. This also hardens
  the existing `ReportPortalLogs` API. Other backends (e.g. log4j2) can read `RpMdc.ITEM_KEY` or forward
  events to `ReportPortalLogs.log(...)`.

### Changed

- `ReportPortalLogs` now resolves the active test via the MDC item key instead of a per-thread stack,
  so it works off the test-body thread and under concurrent execution. (Previously such calls could be
  dropped or misattributed.)
- Internal logging resolution simplified to a single source of truth (key registry); the redundant
  per-thread stack was removed.

### Notes

- ReportPortal renders defect badges on `STEP` leaves only — keep `leafItemType = STEP` (the default)
  when relying on `defectTypeResolver` / `failureDefectType`.

## [1.1.0] - 2025

First release with the current public API and decomposed architecture.

### Added

- `RpConfig` for reporting behavior: `syntheticRootSuite` / `rootSuiteName`, `leafItemType`
  (`STEP`/`TEST`), `reportIgnored`, `failureDefectType`, `defectTypeResolver`, and programmatic
  `ListenerParameters`.
- `ReportPortalLogs` public API to attach logs and file/byte attachments to the running test.
- Faithful nested tree (spec/containers as `SUITE`, leaves as `STEP`/`TEST`) across all Kotest spec
  styles, with `prepareSpec`/`finalizeSpec` so each spec is reported exactly once per class regardless
  of `IsolationMode`.
- Attribute mapping: Kotest tags (incl. `key:value`), `severity`, `invocations`, and spec `@Tags`.
- Failure reporting with stacktrace error logs and configurable defect types (`RpDefect`).
- Launch identity attributes (`agent`, `os`, `jvm`) and CI support: rerun mode and distributed launches
  (client-join and external launch UUID).
- Tag-driven automatic publishing to Maven Central.

## [1.0.3] - 2025

- Earlier iterations of the extension. See the Git history and tags (`1.0.1`–`1.0.3`) for details.

[Unreleased]: https://github.com/qasecret/kotest-rp/compare/v1.1.0...HEAD
[1.2.0]: https://github.com/qasecret/kotest-rp/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/qasecret/kotest-rp/releases/tag/v1.1.0
[1.0.3]: https://github.com/qasecret/kotest-rp/releases/tag/1.0.3
