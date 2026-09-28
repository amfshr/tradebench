// Shared domain types + the clock/event-source SPI seat. Depends on nothing of ours.
plugins {
    `java-library`
}

group = "dev.amfshr.tradebench"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    api(libs.jspecify)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
