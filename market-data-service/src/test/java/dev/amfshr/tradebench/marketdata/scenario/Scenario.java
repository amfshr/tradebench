package dev.amfshr.tradebench.marketdata.scenario;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/** A timeline: what happens, at what offset from the start, to which markets, for how long. */
public final class Scenario {

    public record Step(Duration at, Event event) {
    }

    public final String name;
    public final List<String> epics;
    public final List<Step> steps;
    public final Duration until;
    /** The server answers as a healthy one would — every connection greeted with
     * {@code CONNECTED:WS-STREAMING}, every subscription confirmed — for a source that recorded
     * no statuses (a replay). The policy scenarios script every answer themselves. */
    public final boolean serverAnswers;
    /** The wall instant at offset zero — a replay's, so its rows carry their real dates; null
     * leaves the clock at its default start. */
    public final @Nullable Instant origin;
    /** Epics the server refuses outright: every subscription asked for one is rejected with the
     * code — the bad-epic case, whose attempts only the belt decides the timing of. */
    public final Map<String, Integer> serverRejects;

    private Scenario(String name, List<String> epics, List<Step> steps, Duration until,
            boolean serverAnswers, @Nullable Instant origin, Map<String, Integer> serverRejects) {
        this.name = name;
        this.epics = List.copyOf(epics);
        this.steps = List.copyOf(steps);
        this.until = until;
        this.serverAnswers = serverAnswers;
        this.origin = origin;
        this.serverRejects = Map.copyOf(serverRejects);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private final List<String> epics = new ArrayList<>();
        private final List<Step> steps = new ArrayList<>();
        private Duration until = Duration.ofMinutes(5);
        private boolean serverAnswers;
        private @Nullable Instant origin;
        private final Map<String, Integer> serverRejects = new HashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder markets(String... epics) {
            this.epics.addAll(List.of(epics));
            return this;
        }

        /** Events at one offset, delivered in the order given. */
        public Builder at(Duration at, Event... events) {
            for (Event event : events) {
                steps.add(new Step(at, event));
            }
            return this;
        }

        /** One event per {@code period} from {@code from} (inclusive) to {@code to} (exclusive). */
        public Builder every(Duration period, Duration from, Duration to,
                Function<Duration, Event> event) {
            for (Duration t = from; t.compareTo(to) < 0; t = t.plus(period)) {
                steps.add(new Step(t, event.apply(t)));
            }
            return this;
        }

        /** When the scenario ends: no event scheduled at or past it is delivered; the sweeps and
         * heartbeats that fall due exactly at it still run (the other threads' last turn). */
        public Builder until(Duration until) {
            this.until = until;
            return this;
        }

        public Builder serverAnswers() {
            this.serverAnswers = true;
            return this;
        }

        public Builder origin(Instant origin) {
            this.origin = origin;
            return this;
        }

        public Builder serverRejects(String epic, int code) {
            serverRejects.put(epic, code);
            return this;
        }

        public Scenario build() {
            List<Step> ordered = new ArrayList<>(steps);
            ordered.sort(Comparator.comparing(Step::at)); // stable: same-offset events keep their order
            return new Scenario(name, epics, ordered, until, serverAnswers, origin, serverRejects);
        }
    }
}
