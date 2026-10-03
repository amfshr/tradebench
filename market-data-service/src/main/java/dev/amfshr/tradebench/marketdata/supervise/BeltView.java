package dev.amfshr.tradebench.marketdata.supervise;

import dev.amfshr.tradebench.marketdata.events.StreamState;

/**
 * The belt's read-only health view for the heartbeat thread: each market's stream state and the
 * lifetime reconnect count. The {@link Supervisor} implements it from volatile snapshots written
 * on its sweep thread; nothing here blocks, decides, or touches the cores.
 */
public interface BeltView {

    StreamState stateOf(String epic);

    long reconnectsTotal();
}
