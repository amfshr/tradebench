// The deployable collection-service app (Spring Boot arrives with E1-T2/T3).
// Apps depend on libraries, never sideways between apps (phase-1 build plan §2).
plugins {
    application
}

application {
    mainClass = "dev.amfshr.tradebench.marketdata.capture.CaptureRunner"
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
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
