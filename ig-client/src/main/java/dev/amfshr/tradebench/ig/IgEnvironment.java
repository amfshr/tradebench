package dev.amfshr.tradebench.ig;

/**
 * IG environment selection. One flag picks base URL, API key, and credentials together
 * (playbook §1.5): demo and live have separate keys, account numbers, and hosts — flipping
 * environments must require nothing but this choice.
 */
public enum IgEnvironment {
    DEMO("https://demo-api.ig.com/gateway/deal", "IG_DEMO_"),
    LIVE("https://api.ig.com/gateway/deal", "IG_LIVE_");

    private final String baseUrl;
    private final String envPrefix;

    IgEnvironment(String baseUrl, String envPrefix) {
        this.baseUrl = baseUrl;
        this.envPrefix = envPrefix;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Prefix for this environment's credential variables (e.g. {@code IG_DEMO_API_KEY}). */
    public String envPrefix() {
        return envPrefix;
    }
}
