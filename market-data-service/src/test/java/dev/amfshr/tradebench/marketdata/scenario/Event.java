package dev.amfshr.tradebench.marketdata.scenario;

import java.time.Duration;

/** One thing the world does to the pipeline at an offset of a scenario — the fixture vocabulary
 * (E1-T11): the stream's statuses and subscription outcomes, data, the database, the host, the
 * operator. A captured outage is a list of these, so is a hand-written policy scenario. */
public sealed interface Event {

    enum Leg { PRICE, CHART }

    /** A Lightstreamer connection status, e.g. {@code CONNECTED:WS-STREAMING}. */
    record Status(String status) implements Event {
    }

    /** The server refused us — followed, in the SDK's contract, by a bare DISCONNECTED. */
    record ServerError(int code, String message) implements Event {
    }

    /** The server accepted one leg's subscription. */
    record Confirm(String epic, Leg leg) implements Event {
    }

    /** The server rejected one leg's subscription. */
    record Reject(String epic, Leg leg, int code, String message) implements Event {
    }

    /** A price update on the PRICE leg; the deal flag drives the watchdog's stand-down. */
    record Tick(String epic, String bid, String ask, String dealFlag) implements Event {
    }

    /** A sealed 1-minute candle on the CHART leg, for the current wall minute. */
    record Bar(String epic, String open, String high, String low, String close) implements Event {
    }

    /** Postgres goes away: the sink, the event log and the status store all fail retryably. */
    record DbDown() implements Event {
    }

    record DbUp() implements Event {
    }

    /** Postgres answers, but wrongly: the next write fails for a reason no retry will fix (a
     * schema error) — the blip taxonomy (D28) does not name it. */
    record DbBroken() implements Event {
    }

    /** The lid closes: the wall clock jumps, the monotonic clock does not. */
    record HostSleep(Duration by) implements Event {
    }

    /** IG stops accepting connections and logins. */
    record IgDown() implements Event {
    }

    record IgUp() implements Event {
    }

    /** Ctrl-C: the shutdown order runs. */
    record Stop() implements Event {
    }
}
