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
    implementation("io.github.qa-secret:kotest-rp:<version>")
}
```

#### Ensure you are using the correct version for the reportportal-kotest-extension. Replace <version> with the latest 
#### version available on Maven.
### 2. configure project config with ReportPortal extension

```kotlin
class ProjectConfig : AbstractProjectConfig() {
    override fun extensions(): List<Extension> {
        return listOf(ReportPortalExtension()) 
    }
}
```

