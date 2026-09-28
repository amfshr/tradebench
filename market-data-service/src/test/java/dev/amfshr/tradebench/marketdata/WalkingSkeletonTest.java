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
        // Cross-module wiring proven against one real type from each library module.
        assertEquals("https://demo-api.ig.com/gateway/deal", IgEnvironment.DEMO.baseUrl());
        assertEquals("core-check", new dev.amfshr.tradebench.core.domain.Tick(
                "core-check", java.time.Instant.EPOCH, java.math.BigDecimal.ONE,
                java.math.BigDecimal.TWO).epic());
    }
}
