import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "2.0.0"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "io.github.qasecret"
version = "1.0.3"

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
    compileOnly("io.kotest:kotest-framework-api:$kotestVersion")
    compileOnly("io.kotest:kotest-framework-engine-jvm:$kotestVersion")

    // Tests run the real Kotest engine and assert against a recording ReportPortal client.
    testImplementation("io.kotest:kotest-framework-api:$kotestVersion")
    testImplementation("io.kotest:kotest-framework-engine-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.kotest:kotest-runner-junit5-jvm:$kotestVersion")
    // Needed only to implement the ReportPortalClient.log(List<MultipartBody.Part>) member in the
    // recording test fake; the type comes transitively from client-java at runtime.
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.15")
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
    // Forward rp.* system properties (e.g. rp.live, rp.shard.*) to the forked test JVM so the
    // opt-in live ReportPortal smoke tests can be driven from the Gradle command line.
    System.getProperties().forEach { (k, v) ->
        if (k is String && k.startsWith("rp.")) systemProperty(k, v.toString())
    }
}
kotlin {
    jvmToolchain(17)
}