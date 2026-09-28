package dev.amfshr.tradebench.ig.stream;

/**
 * Parsed-update sink. Called on the Lightstreamer session thread — implementations must be
 * cheap, non-blocking, and non-throwing (golden rule 5: hand off to a queue and return).
 */
public interface StreamEvents {

    void onTick(TickUpdate tick);

    void onSealedBar(SealedBarUpdate bar);

    /** A structurally broken update was dropped (in-progress candles are NOT malformed). */
    void onMalformed(String itemName);
}
