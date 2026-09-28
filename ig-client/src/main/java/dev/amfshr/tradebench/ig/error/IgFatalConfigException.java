package dev.amfshr.tradebench.ig.error;

import org.jspecify.annotations.Nullable;

/**
 * Retry can never fix this (bad key, suspended account, invalid config). Callers must stop
 * immediately — hammering IG's login endpoint on a fatal error risks key revocation (§1.6).
 */
public final class IgFatalConfigException extends IgApiException {

    public IgFatalConfigException(String message) {
        this(message, 0, null);
    }

    public IgFatalConfigException(String message, int httpStatus, @Nullable String errorCode) {
        super(message + " — fatal config: will not retry; fix the configuration", httpStatus,
                errorCode);
    }

    @Override
    public IgErrorClass errorClass() {
        return IgErrorClass.FATAL_CONFIG;
    }
}
