package dev.amfshr.tradebench.marketdata.heal;

/** What the record says about one minute of a market's day. */
public enum MinuteState {
    /** IG's flag said DEAL during the minute while the collector observed it: a missing bar is a gap. */
    OPEN,
    /** IG's flag said anything else for the whole minute: never a gap. */
    CLOSED,
    /** The collector was not observing, or IG had not yet said: asked of IG's history. */
    UNKNOWN
}
