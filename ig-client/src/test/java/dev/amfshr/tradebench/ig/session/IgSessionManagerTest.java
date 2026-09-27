package dev.amfshr.tradebench.ig.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.ig.IgCredentials;
import dev.amfshr.tradebench.ig.IgEnvironment;
import dev.amfshr.tradebench.ig.error.IgFatalConfigException;
import dev.amfshr.tradebench.ig.error.IgRetryableException;
import dev.amfshr.tradebench.ig.http.HttpCall;
import dev.amfshr.tradebench.ig.rest.RequestPacer;
import dev.amfshr.tradebench.ig.testutil.FakeTime;
import dev.amfshr.tradebench.ig.testutil.FakeTransport;
import dev.amfshr.tradebench.ig.testutil.Wire;

/**
 * The §1.1 bring-up sequence and §1.2 rebuild discipline. Anchors are the wire facts from
 * the playbook (fixture: profile prefers CFD Z6CS3D; we trade SPREADBET Z6CS3E).
 */
class IgSessionManagerTest {

    private static final String SWITCH_OK_BODY =
            "{\"dealingEnabled\":true,\"hasActiveDemoAccounts\":true,"
                    + "\"hasActiveLiveAccounts\":true,\"trailingStopsEnabled\":false}";

    private FakeTransport transport;
    private FakeTime time;
    private IgSessionManager manager;

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        time = new FakeTime();
        IgCredentials credentials =
                new IgCredentials("amfshr-demo", "pw", "placeholder-key", "Z6CS3E");
        manager = new IgSessionManager(transport, IgEnvironment.DEMO, credentials,
                new RequestPacer(30, time.clock(), time.sleeper()),
                new LoginRateGate(time.clock(), time.sleeper()));
    }

    @Test
    void loginLandsOnPreferredAccountThenSwitchesAndReReadsTokens() throws Exception {
        transport.enqueue(FakeTransport.jsonWithTokens(200, Wire.fixture("login-response.json"),
                "loginCst", "loginXst"));
        transport.enqueue(FakeTransport.jsonWithTokens(200, SWITCH_OK_BODY,
                "switchedCst", "switchedXst"));

        IgSession session = manager.current();

        // Tokens MUST be the switch response's — switching refreshes them (§1.4). Reading the
        // login tokens here is exactly the "streaming connects but shows nothing" bug.
        assertEquals(new IgTokens("switchedCst", "switchedXst"), session.tokens());
        assertEquals("Z6CS3E", session.activeAccountId());
        assertEquals("https://demo-apd.marketdatasystems.com", session.lightstreamerEndpoint());
        assertEquals(List.of(
                new IgAccount("Z6CS3D", "CFD", true),
                new IgAccount("Z6CS3E", "SPREADBET", false)), session.accounts());
        assertEquals("CST-switchedCst|XST-switchedXst", session.tokens().lightstreamerPassword());
    }

    @Test
    void loginSendsV2WithApiKeyAndSwitchSendsV1WithSessionTokens() throws Exception {
        transport.enqueue(FakeTransport.jsonWithTokens(200, Wire.fixture("login-response.json"),
                "loginCst", "loginXst"));
        transport.enqueue(FakeTransport.jsonWithTokens(200, SWITCH_OK_BODY, "c2", "x2"));

        manager.current();

        HttpCall login = transport.calls.get(0);
        assertEquals("POST", login.method());
        assertEquals("https://demo-api.ig.com/gateway/deal/session", login.uri().toString());
        assertEquals("2", login.headers().get("VERSION"));
        assertEquals("placeholder-key", login.headers().get("X-IG-API-KEY"));
        assertEquals("{\"identifier\":\"amfshr-demo\",\"password\":\"pw\"}", login.body());

        HttpCall switchCall = transport.calls.get(1);
        assertEquals("PUT", switchCall.method());
        assertEquals("1", switchCall.headers().get("VERSION"));
        assertEquals("loginCst", switchCall.headers().get("CST"));
        assertEquals("loginXst", switchCall.headers().get("X-SECURITY-TOKEN"));
        assertEquals("{\"accountId\":\"Z6CS3E\",\"defaultAccount\":false}", switchCall.body());
    }

    @Test
    void noSwitchWhenLoginAlreadyLandsOnTheConfiguredAccount() throws Exception {
        String alreadyRight = Wire.fixture("login-response.json")
                .replace("\"currentAccountId\": \"Z6CS3D\"", "\"currentAccountId\": \"Z6CS3E\"");
        transport.enqueue(FakeTransport.jsonWithTokens(200, alreadyRight, "loginCst", "loginXst"));

        IgSession session = manager.current();

        assertEquals(1, transport.calls.size(), "no PUT /session when already on the account");
        assertEquals(new IgTokens("loginCst", "loginXst"), session.tokens());
    }

    @Test
    void benignAlreadyOnAccountSwitchErrorKeepsLoginTokens() throws Exception {
        transport.enqueue(FakeTransport.jsonWithTokens(200, Wire.fixture("login-response.json"),
                "loginCst", "loginXst"));
        transport.enqueue(FakeTransport.json(412,
                "{\"errorCode\":\"error.switch.accountId-must-be-different\"}"));

        IgSession session = manager.current();

        assertEquals(new IgTokens("loginCst", "loginXst"), session.tokens());
        assertEquals("Z6CS3E", session.activeAccountId());
    }

    @Test
    void fatalLoginErrorStopsImmediatelyWithoutRetry() {
        transport.enqueue(FakeTransport.json(403,
                "{\"errorCode\":\"error.security.api-key-invalid\"}"));

        IgFatalConfigException thrown =
                assertThrows(IgFatalConfigException.class, () -> manager.current());

        assertEquals("error.security.api-key-invalid", thrown.errorCode());
        assertEquals(1, transport.calls.size(), "fatal config must not be retried (§1.6)");
    }

    @Test
    void invalidSwitchAccountIsFatal() {
        transport.enqueue(FakeTransport.jsonWithTokens(200, Wire.fixture("login-response.json"),
                "loginCst", "loginXst"));
        transport.enqueue(FakeTransport.json(400,
                "{\"errorCode\":\"error.switch.invalid-accountId\"}"));

        assertThrows(IgFatalConfigException.class, () -> manager.current());
    }

    @Test
    void switchResponseWithoutTokenHeadersIsRefused() {
        // Switching refreshes tokens (§1.4): a 200 without token headers would leave us on
        // stale pre-switch tokens — the "connected but shows nothing" bug, deferred.
        transport.enqueue(FakeTransport.jsonWithTokens(200, Wire.fixture("login-response.json"),
                "loginCst", "loginXst"));
        transport.enqueue(FakeTransport.json(200, SWITCH_OK_BODY));

        assertThrows(IgRetryableException.class, () -> manager.current());
    }

    @Test
    void loginResponseWithoutTokenHeadersIsRetryable() {
        transport.enqueue(FakeTransport.json(200, Wire.fixture("login-response.json")));

        assertThrows(IgRetryableException.class, () -> manager.current());
    }

    @Test
    void afterFailureReusesTheSessionWhenValidationSucceeds() throws Exception {
        transport.enqueue(FakeTransport.jsonWithTokens(200, Wire.fixture("login-response.json"),
                "loginCst", "loginXst"));
        transport.enqueue(FakeTransport.jsonWithTokens(200, SWITCH_OK_BODY, "c2", "x2"));
        IgSession original = manager.current();

        transport.enqueue(FakeTransport.json(200, "{\"accountId\":\"Z6CS3E\"}"));
        IgSession revalidated = manager.afterFailure();

        // Rebuilds must NOT re-login while tokens live — rapid logins get IG's cached,
        // stale-token response (§1.2). GET /session is the cheap validation.
        assertSame(original, revalidated);
        HttpCall validation = transport.calls.get(2);
        assertEquals("GET", validation.method());
        assertEquals("https://demo-api.ig.com/gateway/deal/session", validation.uri().toString());
        assertEquals("c2", validation.headers().get("CST"));
    }

    @Test
    void afterFailureLogsInFreshWhenTokensAreDeadAndHonoursTheStagger() throws Exception {
        transport.enqueue(FakeTransport.jsonWithTokens(200, Wire.fixture("login-response.json"),
                "loginCst", "loginXst"));
        transport.enqueue(FakeTransport.jsonWithTokens(200, SWITCH_OK_BODY, "c2", "x2"));
        manager.current();
        time.advance(Duration.ofSeconds(30));

        transport.enqueue(FakeTransport.json(401,
                "{\"errorCode\":\"error.security.account-token-invalid\"}"));
        transport.enqueue(FakeTransport.jsonWithTokens(200, Wire.fixture("login-response.json"),
                "freshCst", "freshXst"));
        transport.enqueue(FakeTransport.jsonWithTokens(200, SWITCH_OK_BODY, "c3", "x3"));

        IgSession rebuilt = manager.afterFailure();

        assertEquals(new IgTokens("c3", "x3"), rebuilt.tokens());
        // 61s minimum between fresh logins, 30s elapsed → the gate must wait exactly 31s.
        assertTrue(time.sleeps.contains(Duration.ofSeconds(31)),
                "expected a 31s stagger sleep, got: " + time.sleeps);
    }

    @Test
    void fatalErrorDuringValidationStopsInsteadOfRetryLooping() throws Exception {
        transport.enqueue(FakeTransport.jsonWithTokens(200, Wire.fixture("login-response.json"),
                "loginCst", "loginXst"));
        transport.enqueue(FakeTransport.jsonWithTokens(200, SWITCH_OK_BODY, "c2", "x2"));
        manager.current();

        transport.enqueue(FakeTransport.json(403,
                "{\"errorCode\":\"error.security.api-key-disabled\"}"));

        assertThrows(IgFatalConfigException.class, () -> manager.afterFailure());
        assertEquals(3, transport.calls.size(), "a revoked key must not trigger a login storm");
    }
}
