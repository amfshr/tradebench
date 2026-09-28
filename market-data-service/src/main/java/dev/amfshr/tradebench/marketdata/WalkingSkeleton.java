package dev.amfshr.tradebench.marketdata;

/**
 * Proves this module's build/test/CI wiring end to end (E1-T1 walking skeleton).
 * Deliberately disposable: delete when the module's first real class lands (ig-client's
 * already has — its wiring is now proven against real classes in the test).
 */
public final class WalkingSkeleton {

    private WalkingSkeleton() {
    }

    public static String moduleName() {
        return "market-data-service";
    }
}
