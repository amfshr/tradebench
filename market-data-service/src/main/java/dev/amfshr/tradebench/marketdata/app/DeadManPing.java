package dev.amfshr.tradebench.marketdata.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

import dev.amfshr.tradebench.marketdata.events.LogLine;

/**
 * The dead-man ping (D33, E1-T7): one GET a minute from the heartbeat to a healthchecks.io check, which
 * alarms when the pings stop — the deaths that leave no row (kill -9, OOM, a reboot, a database down at
 * the moment of death). No data leaves. A failed ping is said and never stops the collector: the check's
 * silence is the alarm, not the collector's.
 */
final class DeadManPing {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final URI url;
    private final Consumer<String> log;
    private final Duration timeout;

    DeadManPing(URI url, Consumer<String> log) {
        this(url, log, TIMEOUT);
    }

    /** As above, bounded by {@code timeout} — the heartbeat thread also carries the lock check and the
     * thread-death backstops, so a stalled check must never hold it. */
    DeadManPing(URI url, Consumer<String> log, Duration timeout) {
        this.url = url;
        this.log = log;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    void ping() {
        try {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(url).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                log.accept(LogLine.warn("dead-man ping refused — HTTP " + response.statusCode() + "; the check will alarm if it persists"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.accept(LogLine.warn("dead-man ping failed — " + e.getClass().getSimpleName() + "; the check will alarm if it persists"));
        }
    }
}
