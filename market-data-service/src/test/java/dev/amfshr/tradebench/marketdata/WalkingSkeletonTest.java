package dev.amfshr.tradebench.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.ig.IgEnvironment;

class WalkingSkeletonTest {

    @Test
    void identifiesItsModule() {
        assertEquals("market-data-service", WalkingSkeleton.moduleName());
    }

    @Test
    void seesLibraryDependenciesOnTheClasspath() {
        // Cross-module wiring: one real ig-client type, one core type (still a skeleton).
        assertEquals("https://demo-api.ig.com/gateway/deal", IgEnvironment.DEMO.baseUrl());
        assertEquals("core", dev.amfshr.tradebench.core.WalkingSkeleton.moduleName());
    }
}
