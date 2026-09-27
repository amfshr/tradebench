package dev.amfshr.tradebench.ig.session;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.amfshr.tradebench.ig.IgCredentials;
import dev.amfshr.tradebench.ig.IgEnvironment;
import dev.amfshr.tradebench.ig.error.IgErrorClass;
import dev.amfshr.tradebench.ig.error.IgErrorClassifier;
import dev.amfshr.tradebench.ig.error.IgErrors;
import dev.amfshr.tradebench.ig.error.IgFatalConfigException;
import dev.amfshr.tradebench.ig.error.IgRetryableException;
import dev.amfshr.tradebench.ig.http.HttpCall;
import dev.amfshr.tradebench.ig.http.HttpResult;
import dev.amfshr.tradebench.ig.http.HttpTransport;
import dev.amfshr.tradebench.ig.rest.RequestPacer;

/**
 * Owns the v2 CST/X-SECURITY-TOKEN session lifecycle (§1.1–§1.4):
 *
 * <p><b>Login sequence — explicit, always:</b> POST /session lands on the profile's
 * <em>preferred</em> account (there is no login parameter to choose); if that is not the
 * configured account, PUT /session switches ({@code defaultAccount:false} — session-only,
 * never the profile default) and the tokens are <b>re-read from the switch response</b>
 * because switching refreshes them. Skipping any step reproduces the "streaming connects
 * but shows nothing" bug.
 *
 * <p><b>Rebuilds reuse before re-login</b> (§1.2): {@link #afterFailure()} validates the
 * cached tokens with a cheap GET /session and only logs in fresh when they are genuinely
 * dead — rapid re-logins hit IG's response cache and receive stale tokens.
 */
public final class IgSessionManager {

    /** Benign: PUT /session when already on the target account (§1.4). */
    private static final String ALREADY_ON_ACCOUNT = "error.switch.accountid-must-be-different";

    private final HttpTransport transport;
    private final IgEnvironment environment;
    private final IgCredentials credentials;
    private final RequestPacer pacer;
    private final LoginRateGate loginGate;
    private final ObjectMapper mapper = new ObjectMapper();

    private IgSession session;

    public IgSessionManager(HttpTransport transport, IgEnvironment environment,
            IgCredentials credentials, RequestPacer pacer, LoginRateGate loginGate) {
        this.transport = transport;
        this.environment = environment;
        this.credentials = credentials;
        this.pacer = pacer;
        this.loginGate = loginGate;
    }

    /** The current session, logging in fresh only if none exists yet. */
    public synchronized IgSession current() throws IOException, InterruptedException {
        if (session == null) {
            session = freshLogin();
        }
        return session;
    }

    /**
     * The rebuild path after any failure: validate the cached session with a side-effect-free
     * GET /session; only when the tokens are genuinely dead, drop it and log in fresh.
     */
    public synchronized IgSession afterFailure() throws IOException, InterruptedException {
        if (session != null && stillValid(session)) {
            return session;
        }
        session = freshLogin();
        return session;
    }

    private boolean stillValid(IgSession candidate) throws IOException, InterruptedException {
        pacer.acquire();
        HttpResult result = transport.exchange(new HttpCall("GET",
                URI.create(environment.baseUrl() + "/session"), authedHeaders(1, candidate.tokens()),
                null));
        if (result.status() == 200) {
            return true;
        }
        // A failing GET /session means the tokens are dead (§1.6) — unless the code says the
        // config itself is broken, in which case retrying a fresh login would hammer a lockout.
        throwIfFatal(result, "GET /session validation");
        return false;
    }

    private IgSession freshLogin() throws IOException, InterruptedException {
        loginGate.awaitLoginTurn();
        pacer.acquire();
        String body = mapper.createObjectNode()
                .put("identifier", credentials.identifier())
                .put("password", credentials.password())
                .toString();
        HttpResult login = transport.exchange(new HttpCall("POST",
                URI.create(environment.baseUrl() + "/session"), baseHeaders(2), body));
        if (login.status() != 200) {
            throw IgErrors.from(mapper, login, "POST /session login");
        }
        JsonNode root = mapper.readTree(login.body());
        IgTokens tokens = tokensFrom(login)
                .orElseThrow(() -> new IgRetryableException(
                        "Login response carried no CST/X-SECURITY-TOKEN headers", 200, null));
        String currentAccountId = root.path("currentAccountId").asText();
        List<IgAccount> accounts = accountsFrom(root);
        String lightstreamerEndpoint = root.path("lightstreamerEndpoint").asText();

        if (!credentials.accountId().equals(currentAccountId)) {
            tokens = switchAccount(tokens);
        }
        return new IgSession(tokens, credentials.accountId(), lightstreamerEndpoint, accounts);
    }

    /**
     * PUT /session to the configured account. {@code defaultAccount:false} switches only this
     * session; {@code true} would permanently rewrite the profile's preferred account (§1.4).
     * Tokens are re-read from the switch response — switching refreshes them.
     */
    private IgTokens switchAccount(IgTokens loginTokens) throws IOException, InterruptedException {
        pacer.acquire();
        String body = mapper.createObjectNode()
                .put("accountId", credentials.accountId())
                .put("defaultAccount", false)
                .toString();
        HttpResult switched = transport.exchange(new HttpCall("PUT",
                URI.create(environment.baseUrl() + "/session"), authedHeaders(1, loginTokens),
                body));
        if (switched.status() != 200) {
            String code = IgErrors.errorCode(mapper, switched);
            if (code != null && code.equalsIgnoreCase(ALREADY_ON_ACCOUNT)) {
                return loginTokens;
            }
            throw IgErrors.from(mapper, switched, "PUT /session account switch");
        }

        return tokensFrom(switched).orElseThrow(() -> new IgRetryableException(
                "Account switch response carried no CST/X-SECURITY-TOKEN headers", 200, null));
    }

    private Map<String, String> baseHeaders(int version) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-IG-API-KEY", credentials.apiKey());
        headers.put("VERSION", String.valueOf(version));
        headers.put("Content-Type", "application/json; charset=UTF-8");
        headers.put("Accept", "application/json; charset=UTF-8");
        return headers;
    }

    private Map<String, String> authedHeaders(int version, IgTokens tokens) {
        Map<String, String> headers = baseHeaders(version);
        headers.put("CST", tokens.cst());
        headers.put("X-SECURITY-TOKEN", tokens.securityToken());
        return headers;
    }

    private static java.util.Optional<IgTokens> tokensFrom(HttpResult result) {
        var cst = result.firstHeader("CST");
        var xst = result.firstHeader("X-SECURITY-TOKEN");
        if (cst.isPresent() && xst.isPresent()) {
            return java.util.Optional.of(new IgTokens(cst.get(), xst.get()));
        }
        return java.util.Optional.empty();
    }

    private List<IgAccount> accountsFrom(JsonNode root) {
        List<IgAccount> accounts = new ArrayList<>();
        for (JsonNode node : root.path("accounts")) {
            accounts.add(new IgAccount(
                    node.path("accountId").asText(),
                    node.path("accountType").asText(),
                    node.path("preferred").asBoolean()));
        }
        return List.copyOf(accounts);
    }

    private void throwIfFatal(HttpResult result, String operation) {
        String code = IgErrors.errorCode(mapper, result);
        if (IgErrorClassifier.classify(code) == IgErrorClass.FATAL_CONFIG) {
            throw new IgFatalConfigException(operation + " failed", result.status(), code);
        }
    }
}
