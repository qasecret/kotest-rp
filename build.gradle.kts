import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "2.4.0"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "io.github.qasecret"
// Release version is derived automatically from the Git tag on CI (e.g. tag `v1.1.0` -> `1.1.0`),
// or from an explicit VERSION env var (manual workflow_dispatch). Local/dev builds use -SNAPSHOT.
version = run {
    val explicit = System.getenv("VERSION")?.takeIf { it.isNotBlank() }
    val fromTag = System.getenv("GITHUB_REF")
        ?.takeIf { it.startsWith("refs/tags/") }
        ?.substringAfterLast('/')
        ?.removePrefix("v")
    explicit ?: fromTag ?: "2.0.0-SNAPSHOT"
}

repositories {
    mavenCentral()
}

val kotestVersion: String by project

dependencies {
    // ReportPortal client + SLF4J API are the only runtime dependencies. The library binds to
    // slf4j-api and deliberately does NOT ship a logging backend, so consumers keep control of
    // their own (logback, log4j2, etc.) without classpath conflicts.
    implementation("com.epam.reportportal:client-java:5.2.23")
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Kotest is provided by the consumer (this is a Kotest extension); compile against it only.
    // Kotest 6 folded the former `kotest-framework-api` module into `kotest-framework-engine`,
    // so the engine artifact now carries the listener/extension API we compile against.
    compileOnly("io.kotest:kotest-framework-engine-jvm:$kotestVersion")

    // logback is needed ONLY to compile the optional bundled appender (ReportPortalLogbackAppender).
    // compileOnly keeps the policy intact: no logging backend is shipped — consumers using logback
    // already have it; consumers on another backend simply never reference the appender class.
    compileOnly("ch.qos.logback:logback-classic:1.5.35")

    // Tests run the real Kotest engine and assert against a recording ReportPortal client.
    // In Kotest 6 the datatest (`withData`) helpers ship inside the engine module too.
    testImplementation("io.kotest:kotest-framework-engine-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.kotest:kotest-runner-junit5-jvm:$kotestVersion")
    // Needed only to implement the ReportPortalClient.log(List<MultipartBody.Part>) member in the
    // recording test fake; the type comes transitively from client-java at runtime.
    testImplementation("com.squareup.okhttp3:okhttp:5.4.0")
    // logback on the test compile classpath so the appender + concurrency logging tests can use it.
    testImplementation("ch.qos.logback:logback-classic:1.5.35")
}
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    signAllPublications()

    coordinates(group.toString(), "kotest-rp", version.toString())

    pom {
        name = "ReportPortal extension for kotest"
        description = "Provides extension to integrate with report portal"
        inceptionYear = "2025"
        url = "https://github.com/qasecret/kotest-rp"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "qasecret"
                name = "Rabindra Biswal"
                url = "https://github.com/qasecret"
            }
        }
        scm {
            url = "https://github.com/qasecret/kotest-rp"
            connection = "scm:git:git://github.com/qasecret/kotest-rp.git"
            developerConnection = "scm:git:ssh://git@github.com/qasecret/kotest-rp.git"
        }
    }
}

tasks.processResources {
    // Bake the library version into kotest-rp.properties (read at runtime by RpAgent).
    filesMatching("kotest-rp.properties") {
        expand(mapOf("version" to project.version.toString()))
    }
}

tasks.test {
    useJUnitPlatform()
    // The sample specs that the engine tests drive are NESTED classes of the `*Test` classes (e.g.
    // `ReportPortalExtensionEngineTest$FailingSpec`). Kotest 6's JUnit-platform discovery scans for
    // every concrete Spec subclass regardless of nesting/visibility (Kotest 5 left nested ones alone),
    // so without this filter those fixtures — several of which fail or skip on purpose — run directly
    // and pollute the build. The pattern is anchored to the `Test$` nesting boundary (not a bare `$`)
    // so it targets only specs nested inside a `*Test` class and never matches a real test whose own
    // name happens to contain `$`. The in-test `TestEngineLauncher` (a separate engine instance) still
    // launches these fixtures explicitly, unaffected by this post-discovery filter.
    filter {
        excludeTestsMatching("*Test\$*")
    }
    // Forward rp.* system properties (e.g. rp.live, rp.shard.*) to the forked test JVM so the
    // opt-in live ReportPortal smoke tests can be driven from the Gradle command line.
    System.getProperties().forEach { (k, v) ->
        if (k is String && k.startsWith("rp.")) systemProperty(k, v.toString())
    }
}
kotlin {
    jvmToolchain(17)
}

// Kotest 6 marks `TestEngineLauncher` (the in-process engine driver the tests use) `@KotestInternal`.
// It remains the supported way to run the engine from a test, so opt in for the test sources only.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("io.kotest.common.KotestInternal")
}
