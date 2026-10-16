package dev.amfshr.tradebench.marketdata.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import dev.amfshr.tradebench.marketdata.jobs.JobRun;
import dev.amfshr.tradebench.marketdata.jobs.JobRunStore;
import dev.amfshr.tradebench.marketdata.jobs.MarketDay;

/** {@code job_runs} for one instance's user and source; a pooled connection per statement (the
 * jobs thread is low-frequency, like the observability writers). */
public final class PostgresJobRunStore implements JobRunStore {

    private static final String SELECT_RUNS = """
            SELECT started_utc, finished_utc, status, detail::text FROM job_runs
            WHERE job_name = ? AND user_id = ? AND source_id = ? AND instrument_id = ? AND covers_date = ?
            ORDER BY started_utc, id""";
    private static final String INSERT_RUN = """
            INSERT INTO job_runs (job_name, instance, user_id, source_id, instrument_id, covers_date,
                started_utc, finished_utc, status, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""";

    private final DataSource dataSource;
    private final String instance;
    private final short userId;
    private final short sourceId;
    private final InstrumentIds instruments = new InstrumentIds(new ConcurrentHashMap<>());

    public PostgresJobRunStore(DataSource dataSource, String userName, String sourceName, String instance) {
        this.dataSource = dataSource;
        this.instance = instance;
        try (Connection connection = dataSource.getConnection()) {
            this.userId = Jdbc.lookupId(connection, "SELECT id FROM users WHERE name = ?", userName, "user");
            this.sourceId = Jdbc.lookupId(connection, "SELECT id FROM sources WHERE name = ?", sourceName, "source");
        } catch (SQLException e) {
            throw new PersistenceException("job-run store initialisation failed", e);
        }
    }

    @Override
    public List<JobRun> runs(String job, MarketDay day) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_RUNS)) {
            statement.setString(1, job);
            statement.setShort(2, userId);
            statement.setShort(3, sourceId);
            statement.setShort(4, instruments.idFor(connection, day.epic()));
            statement.setObject(5, day.day());
            List<JobRun> runs = new ArrayList<>();
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    runs.add(new JobRun(job, day, r.getObject(1, java.time.OffsetDateTime.class).toInstant(),
                            r.getObject(2, java.time.OffsetDateTime.class).toInstant(),
                            "succeeded".equals(r.getString(3)), r.getString(4)));
                }
            }
            return runs;
        } catch (SQLException e) {
            throw new PersistenceException("job_runs read failed", e);
        }
    }

    @Override
    public void record(JobRun run) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(INSERT_RUN)) {
            statement.setString(1, run.job());
            statement.setString(2, instance);
            statement.setShort(3, userId);
            statement.setShort(4, sourceId);
            statement.setShort(5, instruments.idFor(connection, run.day().epic()));
            statement.setObject(6, run.day().day());
            statement.setObject(7, Jdbc.utc(run.startedUtc()));
            statement.setObject(8, Jdbc.utc(run.finishedUtc()));
            statement.setString(9, run.succeeded() ? "succeeded" : "failed");
            statement.setString(10, run.detailJson());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("job_runs write failed", e);
        }
    }
}
