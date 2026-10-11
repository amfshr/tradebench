package dev.amfshr.tradebench.marketdata.store;

import dev.amfshr.tradebench.marketdata.testutil.PostgresTestBase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.marketdata.store.SingleInstanceLock.Standing;

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

    @Test
    void aHeartbeatOnAHeldLockFindsItHeld() {
        try (SingleInstanceLock lock = SingleInstanceLock.acquire(database)) {
            assertEquals(Standing.HELD, lock.verify());
            assertThrows(IllegalStateException.class, () -> SingleInstanceLock.acquire(database),
                    "still exclusive after the check");
        }
    }

    @Test
    void aLockWhoseSessionDiedIsReacquiredOnAFreshSession() throws Exception {
        // Re-review 1: a Postgres restart the sink holds through (D28) ends every session; the idle
        // lock connection never learns it, and the process would stream on holding no lock. The
        // heartbeat must find the dead session and take the lock again.
        try (SingleInstanceLock lock = SingleInstanceLock.acquire(database)) {
            terminateTheLockHoldersSession();

            assertEquals(Standing.REACQUIRED, lock.verify());
            assertThrows(IllegalStateException.class, () -> SingleInstanceLock.acquire(database),
                    "the fresh session holds it — a second launch is refused again");
            assertEquals(Standing.HELD, lock.verify());
        }
    }

    @Test
    void aLockTakenByAnotherInstanceWhileOurSessionWasDeadIsRefusedLoud() throws Exception {
        // Fail closed: whoever holds the lock runs. If a second launch took it in the gap, we leave.
        SingleInstanceLock ours = SingleInstanceLock.acquire(database);
        terminateTheLockHoldersSession();
        try (SingleInstanceLock theirs = SingleInstanceLock.acquire(database)) {
            IllegalStateException thrown = assertThrows(IllegalStateException.class, ours::verify);
            assertTrue(thrown.getMessage().contains("refusing to run"));
        }
    }

    @Test
    void aLockUnverifiableWhileTheDatabaseIsDownIsAskedAgainNotAbandoned() throws Exception {
        // The hold-through case itself: the database is gone, so is our session — and so is every
        // other instance's chance at the lock. Report, keep capturing, take it back when it returns.
        AtomicBoolean down = new AtomicBoolean();
        SingleInstanceLock.SessionSource sessions = () -> {
            if (down.get()) {
                throw new SQLException("connection refused", "08001");
            }
            return database.dedicatedConnection();
        };
        try (SingleInstanceLock lock = SingleInstanceLock.acquire(sessions)) {
            terminateTheLockHoldersSession();
            down.set(true);

            assertEquals(Standing.UNVERIFIABLE, lock.verify());
            assertEquals(Standing.UNVERIFIABLE, lock.verify(), "asked again, never given up on");

            down.set(false);
            assertEquals(Standing.REACQUIRED, lock.verify());
        }
    }

    /** What a database restart does to the lock's session, without restarting the container: the
     * backend holding our advisory key is terminated and the lock released with it. */
    private static void terminateTheLockHoldersSession() throws Exception {
        String holder = "FROM pg_locks WHERE locktype = 'advisory'"
                + " AND classid::bigint = ? AND objid::bigint = ? AND pid <> pg_backend_pid()";
        try (Connection admin = database.dedicatedConnection()) {
            try (PreparedStatement terminate = admin.prepareStatement("SELECT pg_terminate_backend(pid) " + holder)) {
                terminate.setLong(1, SingleInstanceLock.LOCK_KEY >>> 32);
                terminate.setLong(2, SingleInstanceLock.LOCK_KEY & 0xFFFF_FFFFL);
                try (ResultSet result = terminate.executeQuery()) {
                    assertTrue(result.next(), "a session holding the lock to terminate");
                    assertTrue(result.getBoolean(1));
                }
            }
            try (PreparedStatement gone = admin.prepareStatement("SELECT count(*) " + holder)) {
                gone.setLong(1, SingleInstanceLock.LOCK_KEY >>> 32);
                gone.setLong(2, SingleInstanceLock.LOCK_KEY & 0xFFFF_FFFFL);
                for (int attempt = 0; attempt < 50; attempt++) { // the backend exits asynchronously
                    try (ResultSet result = gone.executeQuery()) {
                        result.next();
                        if (result.getLong(1) == 0) {
                            return;
                        }
                    }
                    Thread.sleep(100);
                }
            }
            throw new AssertionError("the lock holder's session did not end within 5s");
        }
    }
}
