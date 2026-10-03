package dev.amfshr.tradebench.ig.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import dev.amfshr.tradebench.ig.IgCredentials;
import dev.amfshr.tradebench.ig.IgEnvironment;
import dev.amfshr.tradebench.ig.http.JdkHttpTransport;
import dev.amfshr.tradebench.ig.rest.ApplicationAllowance;
import dev.amfshr.tradebench.ig.rest.IgRestClient;
import dev.amfshr.tradebench.ig.rest.RequestPacer;
import dev.amfshr.tradebench.ig.session.IgSession;
import dev.amfshr.tradebench.ig.session.IgSessionManager;
import dev.amfshr.tradebench.ig.session.LoginRateGate;
import dev.amfshr.tradebench.ig.time.Sleeper;

/**
 * The one integration-gated smoke (E1-T2 DoD): a real demo login through the full stack, then
 * the real application allowance (the live anchor for the authored wire fixture). Never runs in
 * CI — excluded by tag; run locally with {@code IG_SMOKE=1 ./gradlew :ig-client:demoSmoke} and
 * IG_DEMO_* set in the environment.
 */
@Tag("ig-demo")
class DemoLoginSmokeTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "IG_SMOKE", matches = "1")
    void logsIntoDemoOnTheConfiguredAccountAndReadsTheRealAllowance() throws Exception {
        IgCredentials credentials = IgCredentials.fromEnv(System.getenv(), IgEnvironment.DEMO);
        JdkHttpTransport http = new JdkHttpTransport();
        RequestPacer pacer = new RequestPacer(RequestPacer.CONSERVATIVE_START, System::nanoTime,
                Sleeper.SYSTEM); // demo enforces 10/min — the published 30 would 403 here
        IgSessionManager manager = new IgSessionManager(http, IgEnvironment.DEMO, credentials, pacer,
                new LoginRateGate(System::nanoTime, Sleeper.SYSTEM));

        IgSession session = manager.current();

        assertEquals(credentials.accountId(), session.activeAccountId());
        assertTrue(session.lightstreamerEndpoint().startsWith("https://"),
                "expected an LS endpoint, got: " + session.lightstreamerEndpoint());
        System.out.println("[smoke] active account: " + session.activeAccountId());
        System.out.println("[smoke] lightstreamer:  " + session.lightstreamerEndpoint());
        System.out.println("[smoke] accounts:       " + session.accounts());

        ApplicationAllowance allowance =
                new IgRestClient(http, IgEnvironment.DEMO, credentials, pacer).applicationAllowance(session);

        assertNotNull(allowance, "our key must appear in GET /operations/application");
        assertTrue(allowance.allowanceAccountOverall() > 0, "a usable per-account budget");
        assertTrue(allowance.allowanceApplicationOverall() > 0, "a usable per-key budget");
        // Numbers only — the entry also carries the API key, which never goes to a log.
        System.out.println("[smoke] allowance: accountOverall=" + allowance.allowanceAccountOverall()
                + " applicationOverall=" + allowance.allowanceApplicationOverall()
                + " accountTrading=" + allowance.allowanceAccountTrading()
                + " historical=" + allowance.allowanceAccountHistoricalData()
                + " concurrentSubscriptions=" + allowance.concurrentSubscriptionsLimit());
    }
}
