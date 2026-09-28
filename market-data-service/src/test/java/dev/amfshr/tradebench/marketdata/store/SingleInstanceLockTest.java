package dev.amfshr.tradebench.marketdata.store;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SingleInstanceLockTest extends PostgresTestBase {

    @Test
    void secondInstanceIsRefusedWhileTheFirstHoldsTheLock() {
        // Each acquire opens its own dedicated session, so this is a genuine
        // cross-session exclusivity check, not advisory-lock re-entrancy.
        try (SingleInstanceLock first = SingleInstanceLock.acquire(database)) {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> SingleInstanceLock.acquire(database));
            assertTrue(thrown.getMessage().contains("refusing to run"));
        }
    }

    @Test
    void closeReleasesTheLockForANewSession() {
        SingleInstanceLock first = SingleInstanceLock.acquire(database);
        first.close();

        // A NEW dedicated session must succeed — this pins release itself: if close()
        // leaked the session (the pooled-connection defect), this acquire is refused.
        try (SingleInstanceLock second = SingleInstanceLock.acquire(database)) {
            assertTrue(true);
        }
    }
}
