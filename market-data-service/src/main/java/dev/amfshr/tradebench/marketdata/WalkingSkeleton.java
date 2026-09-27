package dev.amfshr.tradebench.marketdata;

import java.util.List;

/**
 * Proves this module's build/test/CI wiring end to end (E1-T1 walking skeleton).
 * Deliberately disposable: delete when the module's first real class lands.
 */
public final class WalkingSkeleton {

    private WalkingSkeleton() {
    }

    public static String moduleName() {
        return "market-data-service";
    }

    /** Proves the app module sees its library dependencies on the compile classpath. */
    public static List<String> assembledFrom() {
        return List.of(
                dev.amfshr.tradebench.core.WalkingSkeleton.moduleName(),
                dev.amfshr.tradebench.ig.WalkingSkeleton.moduleName());
    }
}
