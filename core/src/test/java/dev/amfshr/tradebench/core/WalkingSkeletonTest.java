package dev.amfshr.tradebench.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WalkingSkeletonTest {

    @Test
    void identifiesItsModule() {
        assertEquals("core", WalkingSkeleton.moduleName());
    }
}
