package dev.amfshr.tradebench.marketdata.supervise;

import dev.amfshr.tradebench.ig.stream.StreamTransport;

/**
 * What the stream reports back to the resilience belt: connection status and server errors
 * (the transport's own listener) plus each market leg's subscription outcome. The
 * {@link Supervisor} implements it; {@link IgStreamControl} is bound to it so every (re)connect
 * and (re)subscribe reports to the same observer — the one back-edge of the control loop.
 */
public interface StreamObserver extends StreamTransport.ConnectionListener {

    /** A market's PRICE or CHART leg confirmed subscribed. */
    void onSubscribed(String epic, WitnessQuarantine.Kind kind);

    /** A market's PRICE or CHART leg was rejected. */
    void onSubscriptionError(String epic, int code, String message);
}
