plugins {
    // Auto-provisions the Java 21 toolchain when the local JDK differs.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "tradebench"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("core", "ig-client", "market-data-service")
