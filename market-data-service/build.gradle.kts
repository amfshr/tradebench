// The deployable collection-service app (Spring Boot arrives with E1-T2/T3).
// Apps depend on libraries, never sideways between apps (phase-1 build plan §2).
plugins {
    application
}

application {
    mainClass = "dev.amfshr.tradebench.marketdata.app.Main"
}

group = "dev.amfshr.tradebench"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(libs.jspecify)
    implementation(project(":core"))
    implementation(project(":ig-client"))
    implementation(libs.jackson.databind)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    testImplementation(libs.testcontainers.postgresql)
    testImplementation(testFixtures(project(":ig-client"))) // the shared fake Lightstreamer seam
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Default tests run on fakes and a throwaway Postgres in seconds. The replay acceptance suite
// (tag "acceptance", E1-T12) runs captured outages through the real stores into Testcontainers
// Postgres — minutes — so it is excluded from `test`/CI's default run and has its own task and lane.
tasks.test {
    useJUnitPlatform {
        excludeTags("acceptance")
    }
}

tasks.register<Test>("replayAcceptance") {
    group = "verification"
    description = "Replays captured outages through the real stores into a Testcontainers Postgres (E1-T12)."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("acceptance")
    }
    failOnNoDiscoveredTests = true // a lane must never pass vacuously (P9)
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
    }
}
