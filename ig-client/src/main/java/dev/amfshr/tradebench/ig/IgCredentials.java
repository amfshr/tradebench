package dev.amfshr.tradebench.ig;

import java.util.Map;
import java.util.regex.Pattern;

import dev.amfshr.tradebench.ig.error.IgFatalConfigException;

/**
 * Per-environment IG credentials. Loaded from environment variables only — no code defaults
 * for anything security- or account-shaped; a missing key fails at startup naming the exact
 * variable (playbook §1.7).
 */
public record IgCredentials(String identifier, String password, String apiKey, String accountId) {

    /** IG rejects identifiers outside this pattern — note: no dots (playbook §1.7). */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9\\-_]{1,30}");
    /** Account ids allow hyphen but not underscore — asymmetric with identifiers. */
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("[A-Za-z0-9\\-]{1,30}");

    public IgCredentials {
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IgFatalConfigException(
                    "IG identifier '" + identifier + "' violates IG's pattern "
                            + IDENTIFIER_PATTERN.pattern()
                            + " (no dots — 'amfshr.demo' fails where 'amfshr-demo' works)");
        }
        if (!ACCOUNT_PATTERN.matcher(accountId).matches()) {
            throw new IgFatalConfigException(
                    "IG account id '" + accountId + "' violates IG's pattern "
                            + ACCOUNT_PATTERN.pattern() + " (no underscores, no dots)");
        }
    }

    /**
     * Reads {@code <prefix>IDENTIFIER / PASSWORD / API_KEY / ACCOUNT_ID} for the environment
     * (e.g. {@code IG_DEMO_API_KEY}). The env map is a parameter so loading is testable.
     */
    public static IgCredentials fromEnv(Map<String, String> env, IgEnvironment environment) {
        return new IgCredentials(
                required(env, environment.envPrefix() + "IDENTIFIER"),
                required(env, environment.envPrefix() + "PASSWORD"),
                required(env, environment.envPrefix() + "API_KEY"),
                required(env, environment.envPrefix() + "ACCOUNT_ID"));
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IgFatalConfigException(
                    "Missing required environment variable '" + key
                            + "' — no code defaults for credentials; set it in the env file"
                            + " beside the config (never committed)");
        }
        return value;
    }

    @Override
    public String toString() {
        return "IgCredentials[identifier=" + identifier + ", accountId=" + accountId
                + ", password=***, apiKey=***]";
    }
}
