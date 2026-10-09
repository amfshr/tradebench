package dev.amfshr.tradebench.marketdata.supervise;

/** What a connection reports to the Supervisor, each report naming the connection generation
 * that made it — so a superseded connection's last words, enqueued before its gate closed, are
 * never applied to the session that replaced it (E1-T10 #11). */
public interface StreamObserver {

    void onStatusChange(int generation, String status);

    void onServerError(int generation, int code, String message);

    /** A market's PRICE or CHART leg confirmed subscribed. */
    void onSubscribed(int generation, String epic, WitnessQuarantine.Kind kind);

    /** A market's PRICE or CHART leg was rejected. */
    void onSubscriptionError(int generation, String epic, WitnessQuarantine.Kind kind, int code, String message);
}
