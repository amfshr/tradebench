package dev.amfshr.tradebench.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class WalkingSkeletonTest {

    @Test
    void identifiesItsModule() {
        assertEquals("market-data-service", WalkingSkeleton.moduleName());
    }

    @Test
    void assemblesFromBothLibraryModules() {
        assertEquals(List.of("core", "ig-client"), WalkingSkeleton.assembledFrom());
    }
}
