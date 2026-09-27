package dev.amfshr.tradebench.ig.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The real transport: JDK HttpClient (build plan §2 — zero framework deps in this module).
 *
 * <p>Timeouts are injectable: the values here are <em>library defaults</em>, and the
 * composing application owns the policy — including any resilience decoration (retries,
 * circuit breaking) it wants to wrap around {@link HttpTransport}. IG-semantic pacing and
 * login staggering stay in this library because they are provider rules, not policy.
 */
public final class JdkHttpTransport implements HttpTransport {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;
    private final Duration requestTimeout;

    public JdkHttpTransport() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
    }

    public JdkHttpTransport(Duration connectTimeout, Duration requestTimeout) {
        this(HttpClient.newBuilder().connectTimeout(connectTimeout).build(), requestTimeout);
    }

    /** Full control: bring your own {@link HttpClient} (proxy, executor, connect timeout). */
    public JdkHttpTransport(HttpClient client, Duration requestTimeout) {
        this.client = client;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public HttpResult exchange(HttpCall call) throws IOException, InterruptedException {
        HttpResponse<String> response =
                client.send(toRequest(call), HttpResponse.BodyHandlers.ofString());
        return new HttpResult(response.statusCode(), response.headers().map(), response.body());
    }

    // Package-private: the request-mapping contract is testable without a socket.
    HttpRequest toRequest(HttpCall call) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(call.uri())
                .timeout(requestTimeout)
                .method(call.method(), call.body() == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(call.body()));
        call.headers().forEach(builder::header);
        return builder.build();
    }
}
