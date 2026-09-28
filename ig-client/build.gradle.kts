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
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    api(libs.jspecify)
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Default tests run on fakes only. The demo-login smoke (tag "ig-demo") talks to real IG and
// is integration-gated: excluded from `test`/CI, run explicitly via `demoSmoke` with
// IG_SMOKE=1 and the IG_DEMO_* env vars set.
tasks.test {
    useJUnitPlatform {
        excludeTags("ig-demo")
    }
}

tasks.register<Test>("demoSmoke") {
    group = "verification"
    description = "Logs into IG demo (requires IG_SMOKE=1 and IG_DEMO_* env vars)."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("ig-demo")
    }
    // A smoke must never pass vacuously (P9): without the gate the JUnit condition would
    // silently skip the test and a green build would prove nothing — refuse instead.
    doFirst {
        if (System.getenv("IG_SMOKE") != "1") {
            throw GradleException(
                "demoSmoke needs IG_SMOKE=1 (plus IG_DEMO_IDENTIFIER/PASSWORD/API_KEY/"
                        + "ACCOUNT_ID in the environment) — refusing a vacuous green run.")
        }
    }
    // A smoke re-runs every invocation — never UP-TO-DATE.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
