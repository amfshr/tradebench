package dev.amfshr.tradebench.marketdata.scenario;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.store.CaptureStore;
import dev.amfshr.tradebench.marketdata.store.Database;
import dev.amfshr.tradebench.marketdata.store.EventLog;
import dev.amfshr.tradebench.marketdata.store.GapStore;
import dev.amfshr.tradebench.marketdata.store.PersistenceException;
import dev.amfshr.tradebench.marketdata.store.PostgresObservabilityStore;
import dev.amfshr.tradebench.marketdata.store.PostgresStore;
import dev.amfshr.tradebench.marketdata.store.StatusStore;

/**
 * Acceptance mode (E1-T12): the real {@link PostgresStore} and {@link PostgresObservabilityStore}
 * over a migrated Postgres — the same replay, the production write path, the rows read back as
 * the observables. Database weather is the scripted stores' to enact; a replay that asks for it
 * here is refused as a harness error, never answered by pretending.
 */
final class PostgresStores implements Stores {

    static final String USER = "default-user";
    /** The replays are the prototype's demo-account captures. */
    static final String SOURCE = "ig-stream-demo";
    static final String INSTANCE = "acceptance";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSource dataSource;
    private final PostgresStore sink;
    private final PostgresObservabilityStore observability;

    PostgresStores(Database database) {
        this.dataSource = database.dataSource();
        this.sink = new PostgresStore(dataSource, USER, SOURCE);
        this.observability = new PostgresObservabilityStore(dataSource, USER, SOURCE, INSTANCE);
    }

    @Override
    public CaptureStore sink() {
        return sink;
    }

    @Override
    public EventLog events() {
        return observability;
    }

    @Override
    public GapStore gaps() {
        return observability;
    }

    @Override
    public StatusStore status() {
        return observability;
    }

    @Override
    public void apply(Event event) {
        throw new ScenarioRunner.HarnessError("database weather is scripted-mode only — acceptance"
                + " mode replays the stream against a real Postgres: " + event, null);
    }

    /** The rows, in the scripted store's vocabulary: {@code tick@<ms>} and {@code bar@<s>} for
     * what landed, and every {@code service_events} row as an event seen. */
    @Override
    public void collect(Observed observed, Instant start) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery(
                    "SELECT (extract(epoch FROM ts_utc) * 1000)::bigint AS ms FROM ticks ORDER BY ts_utc, id")) {
                while (r.next()) {
                    observed.landed.add("tick@" + r.getLong("ms"));
                }
            }
            try (ResultSet r = s.executeQuery(
                    "SELECT extract(epoch FROM start_utc)::bigint AS s FROM bars_1m ORDER BY start_utc, instrument_id")) {
                while (r.next()) {
                    observed.landed.add("bar@" + r.getLong("s"));
                }
            }
            try (ResultSet r = s.executeQuery("SELECT e.event_type, e.event_time_utc, i.epic,"
                    + " e.detail::text AS detail FROM service_events e"
                    + " LEFT JOIN instruments i ON e.instrument_id = i.id ORDER BY e.event_time_utc, e.id")) {
                while (r.next()) {
                    Instant at = r.getObject("event_time_utc", OffsetDateTime.class).toInstant();
                    observed.events.add(new Observed.Seen(Duration.between(start, at),
                            eventType(r.getString("event_type")), r.getString("epic"), detail(r.getString("detail"))));
                }
            }
        } catch (SQLException e) {
            throw new ScenarioRunner.HarnessError("the rows could not be read back", e);
        }
    }

    @Override
    public void close() {
        try {
            sink.close(); // a no-op after the shutdown order already closed it
        } catch (PersistenceException e) {
            throw new ScenarioRunner.HarnessError("the sink would not close", e);
        }
    }

    private static EventType eventType(String db) {
        for (EventType type : EventType.values()) {
            if (type.db().equals(db)) {
                return type;
            }
        }
        throw new ScenarioRunner.HarnessError("a service_events row with an unknown event_type: " + db, null);
    }

    private static @Nullable JsonNode detail(@Nullable String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new ScenarioRunner.HarnessError("a service_events row with unreadable detail: " + json, e);
        }
    }
}
