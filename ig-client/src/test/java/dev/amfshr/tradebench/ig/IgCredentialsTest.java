package dev.amfshr.tradebench.ig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.ig.error.IgFatalConfigException;

class IgCredentialsTest {

    private static final Map<String, String> COMPLETE_DEMO_ENV = Map.of(
            "IG_DEMO_IDENTIFIER", "demo-trader",
            "IG_DEMO_PASSWORD", "placeholder-password",
            "IG_DEMO_API_KEY", "placeholder-api-key",
            "IG_DEMO_ACCOUNT_ID", "AB12CE");

    @Test
    void loadsTheEnvironmentsOwnVariables() {
        IgCredentials creds = IgCredentials.fromEnv(COMPLETE_DEMO_ENV, IgEnvironment.DEMO);
        assertEquals("demo-trader", creds.identifier());
        assertEquals("placeholder-password", creds.password());
        assertEquals("placeholder-api-key", creds.apiKey());
        assertEquals("AB12CE", creds.accountId());
    }

    @Test
    void missingVariableFailsNamingTheExactKey() {
        Map<String, String> withoutKey = Map.of(
                "IG_DEMO_IDENTIFIER", "demo-trader",
                "IG_DEMO_PASSWORD", "pw",
                "IG_DEMO_ACCOUNT_ID", "AB12CE");
        IgFatalConfigException thrown = assertThrows(IgFatalConfigException.class,
                () -> IgCredentials.fromEnv(withoutKey, IgEnvironment.DEMO));
        assertTrue(thrown.getMessage().contains("IG_DEMO_API_KEY"),
                "startup failure must name the missing variable, got: " + thrown.getMessage());
    }

    @Test
    void liveEnvironmentReadsLivePrefixedVariables() {
        IgFatalConfigException thrown = assertThrows(IgFatalConfigException.class,
                () -> IgCredentials.fromEnv(COMPLETE_DEMO_ENV, IgEnvironment.LIVE));
        assertTrue(thrown.getMessage().contains("IG_LIVE_IDENTIFIER"));
    }

    @Test
    void identifierWithDotIsRejectedUpFront() {
        // IG's pattern forbids dots — 'demo.trader' failed live where 'demo-trader' worked.
        assertThrows(IgFatalConfigException.class,
                () -> new IgCredentials("demo.trader", "pw", "key", "AB12CE"));
    }

    @Test
    void accountIdWithUnderscoreIsRejectedUpFront() {
        // Asymmetric with identifiers: account ids allow hyphen but not underscore.
        assertThrows(IgFatalConfigException.class,
                () -> new IgCredentials("demo-trader", "pw", "key", "AB_2CE"));
    }

    @Test
    void toStringNeverLeaksSecrets() {
        IgCredentials creds = IgCredentials.fromEnv(COMPLETE_DEMO_ENV, IgEnvironment.DEMO);
        String rendered = creds.toString();
        assertFalse(rendered.contains("placeholder-password"), "password leaked: " + rendered);
        assertFalse(rendered.contains("placeholder-api-key"), "api key leaked: " + rendered);
    }
}
