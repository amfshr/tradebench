// The deployable collection-service app — plain Java, no Spring (D6 amended 2026-10-15, D31).
// Apps depend on libraries, never sideways between apps (phase-1 build plan §2).
plugins {
    application
}

application {
    mainClass = "dev.amfshr.tradebench.marketdata.app.Main"
    // DuckDB (the archive's Parquet writer) loads a native library; the JDK will block that unless granted.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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
    implementation(libs.resilience4j.retry)
    // The archive's Parquet writer (E1-T6 nod ruling 3's fallback: parquet-java needs Hadoop at runtime).
    // The all-platforms jar (81 MB) runs anywhere; -PduckdbPlatform=<platform> (linux_amd64 for the box since
    // D34's 2026-10-18 amendment) takes the one platform's (about 20 MB) for the deploy image (E1-T7).
    val duckdbPlatform = providers.gradleProperty("duckdbPlatform").orNull
    if (duckdbPlatform == null) {
        implementation(libs.duckdb.jdbc)
    } else {
        implementation("org.duckdb:duckdb_jdbc:${libs.versions.duckdb.get()}:$duckdbPlatform")
    }
    implementation(libs.awssdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client") // the URL-connection client instead
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation(libs.awssdk.url.connection.client)
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
