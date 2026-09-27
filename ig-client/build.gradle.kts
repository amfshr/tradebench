// IG integration library. Framework-free by rule: consumed by the market-data service, the
// future OMS, the simulator's contract tests, and throwaway CLIs — none of which should drag
// Spring in (phase-1 build plan §2). Depends on nothing of ours.
plugins {
    `java-library`
}

group = "dev.amfshr.tradebench"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
