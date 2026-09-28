package dev.amfshr.tradebench.core.events;

/**
 * The seam "one engine, many clocks" hangs on: ticks and sealed bars arrive as ONE ordered
 * stream, and the engine cannot tell live from replay from fast-forward — each is just a
 * different implementation of this interface. E1 builds only the live implementation (the
 * service's adapter over the IG stream); replay and batch implementations arrive with the
 * backtester (E3+), plugging into the same consumers unchanged.
 */
public interface EventSource extends AutoCloseable {

    /** Starts delivery to the listener. Ordering guarantee: dataTime-ordered per epic. */
    void start(EventListener listener);

    @Override
    void close();
}
