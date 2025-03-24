import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "2.0.0"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "io.github.qasecret"
version = "1.0.0"

repositories {
    mavenCentral()
}

val kotestVersion: String by project
val opentest4jVersion: String by project
val allureVersion: String by project
val slf4jVersion: String by project


dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlin:kotlin-reflect")


    implementation("io.kotest:kotest-framework-api:$kotestVersion") {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation("io.kotest:kotest-framework-engine-jvm:$kotestVersion") {
        exclude(group = "org.jetbrains.kotlin")
    }
    testImplementation("io.kotest:kotest-property:$kotestVersion")
    testImplementation("io.kotest:kotest-framework-datatest:$kotestVersion")
    testImplementation("io.kotest:kotest-runner-junit5-jvm:$kotestVersion") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "io.mockk")
    }
    implementation("com.epam.reportportal:client-java:5.2.23")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.15")
    implementation("ch.qos.logback:logback-core:1.5.13")
    implementation("org.testng:testng:7.9.0")
    implementation("com.epam.reportportal:logger-java-logback:5.2.2")
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

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}