package dev.amfshr.tradebench.ig.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import dev.amfshr.tradebench.ig.IgCredentials;
import dev.amfshr.tradebench.ig.IgEnvironment;
import dev.amfshr.tradebench.ig.http.JdkHttpTransport;
import dev.amfshr.tradebench.ig.rest.RequestPacer;
import dev.amfshr.tradebench.ig.session.IgSession;
import dev.amfshr.tradebench.ig.session.IgSessionManager;
import dev.amfshr.tradebench.ig.session.LoginRateGate;
import dev.amfshr.tradebench.ig.time.Sleeper;

/**
 * The one integration-gated smoke (E1-T2 DoD): a real demo login through the full stack.
 * Never runs in CI — excluded by tag; run locally with
 * {@code IG_SMOKE=1 ./gradlew :ig-client:demoSmoke} and IG_DEMO_* set in the environment.
 */
@Tag("ig-demo")
class DemoLoginSmokeTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "IG_SMOKE", matches = "1")
    void logsIntoDemoOnTheConfiguredAccount() throws Exception {
        IgCredentials credentials = IgCredentials.fromEnv(System.getenv(), IgEnvironment.DEMO);
        IgSessionManager manager = new IgSessionManager(new JdkHttpTransport(),
                IgEnvironment.DEMO, credentials,
                new RequestPacer(RequestPacer.ACCOUNT_NON_TRADING_PER_MINUTE, System::nanoTime,
                        Sleeper.SYSTEM),
                new LoginRateGate(System::nanoTime, Sleeper.SYSTEM));

        IgSession session = manager.current();

        assertEquals(credentials.accountId(), session.activeAccountId());
        assertTrue(session.lightstreamerEndpoint().startsWith("https://"),
                "expected an LS endpoint, got: " + session.lightstreamerEndpoint());
        System.out.println("[smoke] active account: " + session.activeAccountId());
        System.out.println("[smoke] lightstreamer:  " + session.lightstreamerEndpoint());
        System.out.println("[smoke] accounts:       " + session.accounts());
    }
}
