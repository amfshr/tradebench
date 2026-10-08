package dev.amfshr.tradebench.marketdata.scenario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** A timeline: what happens, at what offset from the start, to which markets, for how long. */
public final class Scenario {

    public record Step(Duration at, Event event) {
    }

    public final String name;
    public final List<String> epics;
    public final List<Step> steps;
    public final Duration until;

    private Scenario(String name, List<String> epics, List<Step> steps, Duration until) {
        this.name = name;
        this.epics = List.copyOf(epics);
        this.steps = List.copyOf(steps);
        this.until = until;
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private final List<String> epics = new ArrayList<>();
        private final List<Step> steps = new ArrayList<>();
        private Duration until = Duration.ofMinutes(5);

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

        /** When the scenario ends — exclusive: nothing scheduled at or past it happens. */
        public Builder until(Duration until) {
            this.until = until;
            return this;
        }

        public Scenario build() {
            List<Step> ordered = new ArrayList<>(steps);
            ordered.sort(Comparator.comparing(Step::at)); // stable: same-offset events keep their order
            return new Scenario(name, epics, ordered, until);
        }
    }
}
