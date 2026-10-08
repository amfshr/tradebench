package dev.amfshr.tradebench.marketdata.scenario;

import java.time.Duration;

/** The fixture vocabulary, pronounceable. */
public final class Events {

    public static final String STREAMING = "CONNECTED:WS-STREAMING";
    public static final String WILL_RETRY = "DISCONNECTED:WILL-RETRY";
    public static final String TRYING_RECOVERY = "DISCONNECTED:TRYING-RECOVERY";
    public static final String DISCONNECTED = "DISCONNECTED";

    private Events() {
    }

    public static Event streaming() {
        return new Event.Status(STREAMING);
    }

    public static Event status(String status) {
        return new Event.Status(status);
    }

    public static Event serverError(int code, String message) {
        return new Event.ServerError(code, message);
    }

    /** Both legs confirmed — the normal outcome of a subscribe. */
    public static Event[] subscribed(String epic) {
        return new Event[] {
            new Event.Confirm(epic, Event.Leg.PRICE), new Event.Confirm(epic, Event.Leg.CHART)};
    }

    public static Event confirm(String epic, Event.Leg leg) {
        return new Event.Confirm(epic, leg);
    }

    public static Event reject(String epic, Event.Leg leg, int code) {
        return new Event.Reject(epic, leg, code, "rejected");
    }

    public static Event tick(String epic) {
        return new Event.Tick(epic, "24510.5", "24511.5", "DEAL");
    }

    public static Event tick(String epic, String dealFlag) {
        return new Event.Tick(epic, "24510.5", "24511.5", dealFlag);
    }

    public static Event bar(String epic) {
        return new Event.Bar(epic, "24510", "24520", "24500", "24515");
    }

    public static Event dbDown() {
        return new Event.DbDown();
    }

    public static Event dbUp() {
        return new Event.DbUp();
    }

    public static Event dbBroken() {
        return new Event.DbBroken();
    }

    public static Event hostSleep(Duration by) {
        return new Event.HostSleep(by);
    }

    public static Event igDown() {
        return new Event.IgDown();
    }

    public static Event igUp() {
        return new Event.IgUp();
    }

    public static Event stop() {
        return new Event.Stop();
    }
}
