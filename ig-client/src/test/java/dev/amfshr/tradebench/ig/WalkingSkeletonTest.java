package dev.amfshr.tradebench.ig;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WalkingSkeletonTest {

    @Test
    void identifiesItsModule() {
        assertEquals("ig-client", WalkingSkeleton.moduleName());
    }
}
