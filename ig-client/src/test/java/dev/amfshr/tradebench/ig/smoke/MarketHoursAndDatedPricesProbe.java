package dev.amfshr.tradebench.ig.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import dev.amfshr.tradebench.ig.IgCredentials;
import dev.amfshr.tradebench.ig.IgEnvironment;
import dev.amfshr.tradebench.ig.http.HttpCall;
import dev.amfshr.tradebench.ig.http.HttpResult;
import dev.amfshr.tradebench.ig.http.JdkHttpTransport;
import dev.amfshr.tradebench.ig.rest.RequestPacer;
import dev.amfshr.tradebench.ig.session.IgSession;
import dev.amfshr.tradebench.ig.session.IgSessionManager;
import dev.amfshr.tradebench.ig.session.LoginRateGate;
import dev.amfshr.tradebench.ig.time.Sleeper;

/**
 * E1-T6 nod ruling 5: capture the real wire for the two calls the heal leans on — the market's
 * opening hours (GET /markets/{epic}) and a dated price window (GET /prices/{epic}?from&to) —
 * so the calendar's source and the from/to timezone semantics are read off IG, not assumed. The
 * raw bodies land in build/wire-capture/ (gitignored); they carry market data only. Gated like
 * the login smoke: IG_SMOKE=1 and IG_DEMO_* set.
 */
@Tag("ig-demo")
class MarketHoursAndDatedPricesProbe {

    private static final String EPIC = "IX.D.DAX.DAILY.IP";

    @Test
    @EnabledIfEnvironmentVariable(named = "IG_SMOKE", matches = "1")
    void capturesTheMarketsHoursAndADatedPriceWindow() throws Exception {
        IgCredentials credentials = IgCredentials.fromEnv(System.getenv(), IgEnvironment.DEMO);
        JdkHttpTransport http = new JdkHttpTransport();
        RequestPacer pacer = new RequestPacer(RequestPacer.CONSERVATIVE_START, System::nanoTime, Sleeper.SYSTEM);
        IgSession session = new IgSessionManager(http, IgEnvironment.DEMO, credentials, pacer,
                new LoginRateGate(System::nanoTime, Sleeper.SYSTEM)).current();
        Path out = Path.of("build/wire-capture");
        Files.createDirectories(out);

        HttpResult market = get(http, credentials, session, pacer, "/markets/" + EPIC, 3);
        Files.writeString(out.resolve("market-details-dax.json"), market.body());
        assertEquals(200, market.status(), "GET /markets");

        // 08:00–08:10 written without a zone: the candles' snapshotTimeUTC say how IG read it
        HttpResult prices = get(http, credentials, session, pacer, "/prices/" + EPIC
                + "?resolution=MINUTE&from=2026-10-16T08:00:00&to=2026-10-16T08:10:00&max=20&pageSize=20", 3);
        Files.writeString(out.resolve("prices-dated-window.json"), prices.body());
        assertEquals(200, prices.status(), "GET /prices dated");
    }

    private static HttpResult get(JdkHttpTransport http, IgCredentials credentials, IgSession session,
            RequestPacer pacer, String pathAndQuery, int version) throws Exception {
        pacer.acquire();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-IG-API-KEY", credentials.apiKey());
        headers.put("VERSION", String.valueOf(version));
        headers.put("Accept", "application/json; charset=UTF-8");
        headers.put("CST", session.tokens().cst());
        headers.put("X-SECURITY-TOKEN", session.tokens().securityToken());
        return http.exchange(new HttpCall("GET", URI.create(IgEnvironment.DEMO.baseUrl() + pathAndQuery), headers, null));
    }
}
