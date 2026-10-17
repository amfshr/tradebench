package dev.amfshr.tradebench.marketdata.app;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpServer;

/** E1-T7, D33: a ping reaches the check; a refusing or unreachable check is said, never thrown. */
class DeadManPingTest {

    private final List<String> hits = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aPingGetsTheChecksUrlAndSaysNothingWhenItLands() {
        DeadManPing ping = new DeadManPing(serve(200), log::add);

        ping.ping();

        assertEquals(List.of("GET /ping/a-synthetic-check"), hits);
        assertTrue(log.isEmpty(), "a healthy ping is quiet");
    }

    @Test
    void aCheckThatRefusesIsSaidNotThrown() {
        DeadManPing ping = new DeadManPing(serve(503), log::add);

        assertDoesNotThrow(ping::ping);

        assertTrue(log.getFirst().contains("HTTP 503"), log.toString());
    }

    @Test
    void anUnreachableCheckIsSaidNotThrown() {
        DeadManPing ping = new DeadManPing(URI.create("http://127.0.0.1:1/ping/a-synthetic-check"), log::add);

        assertDoesNotThrow(ping::ping);

        assertTrue(log.getFirst().contains("dead-man ping failed"), log.toString());
    }

    @Test
    @Timeout(10) // an unbounded ping would hang here for ever; fail instead
    void aCheckThatAcceptsButNeverAnswersIsBoundedByTheTimeout() throws Exception {
        // the review's finding 6: the heartbeat thread carries the lock check and the thread backstops too
        try (java.net.ServerSocket stalled = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            DeadManPing ping = new DeadManPing(URI.create("http://127.0.0.1:" + stalled.getLocalPort() + "/ping"),
                    log::add, java.time.Duration.ofMillis(500));
            long started = System.nanoTime();

            ping.ping();

            long tookMs = (System.nanoTime() - started) / 1_000_000;
            assertTrue(tookMs < 3_000, "bounded by its timeout, took " + tookMs + " ms");
            assertTrue(log.getFirst().contains("dead-man ping failed"), log.toString());
        }
    }

    @Test
    void noUrlLeavesThePingOff() {
        assertEquals(null, Main.pingUrl(java.util.Map.of()));
        assertEquals("https://hc-ping.example/a-synthetic-check",
                Main.pingUrl(java.util.Map.of("TRADEBENCH_PING_URL", "https://hc-ping.example/a-synthetic-check")).toString());
    }

    private URI serve(int status) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            hits.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ping/a-synthetic-check");
    }
}
