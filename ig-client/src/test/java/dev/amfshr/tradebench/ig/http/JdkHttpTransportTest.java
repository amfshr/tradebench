package dev.amfshr.tradebench.ig.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Request-mapping contract, tested without a socket via the package-private builder. */
class JdkHttpTransportTest {

    private static final HttpCall CALL = new HttpCall("POST",
            URI.create("https://demo-api.ig.com/gateway/deal/session"),
            Map.of("X-IG-API-KEY", "placeholder-key", "VERSION", "2"),
            "{\"identifier\":\"demo-trader\"}");

    @Test
    void requestCarriesTheInjectedTimeout() {
        JdkHttpTransport transport =
                new JdkHttpTransport(HttpClient.newHttpClient(), Duration.ofSeconds(5));

        HttpRequest request = transport.toRequest(CALL);

        assertEquals(Optional.of(Duration.ofSeconds(5)), request.timeout(),
                "the application-injected timeout must reach the wire request");
    }

    @Test
    void defaultRequestTimeoutIsThirtySeconds() {
        HttpRequest request = new JdkHttpTransport().toRequest(CALL);

        assertEquals(Optional.of(Duration.ofSeconds(30)), request.timeout());
    }

    @Test
    void mapsMethodUriHeadersAndBody() {
        HttpRequest request = new JdkHttpTransport().toRequest(CALL);

        assertEquals("POST", request.method());
        assertEquals("https://demo-api.ig.com/gateway/deal/session", request.uri().toString());
        assertEquals(Optional.of("2"), request.headers().firstValue("VERSION"));
        assertEquals(Optional.of("placeholder-key"),
                request.headers().firstValue("X-IG-API-KEY"));
        assertTrue(request.bodyPublisher().orElseThrow().contentLength() > 0,
                "a call with a body must publish it");
    }

    @Test
    void callWithoutBodyPublishesNoBody() {
        HttpRequest request = new JdkHttpTransport().toRequest(
                new HttpCall("GET", URI.create("https://demo-api.ig.com/gateway/deal/session"),
                        Map.of(), null));

        assertEquals(0, request.bodyPublisher().orElseThrow().contentLength());
    }
}
