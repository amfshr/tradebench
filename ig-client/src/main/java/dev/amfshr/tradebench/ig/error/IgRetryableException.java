package dev.amfshr.tradebench.ig.error;

import org.jspecify.annotations.Nullable;

/**
 * A session rebuild / backoff retry can fix this (token expiry, rate limit, transient server
 * error). Callers retry with backoff — floor, ceiling, and a consecutive-failure stop (§3.2);
 * nothing retries forever.
 */
public final class IgRetryableException extends IgApiException {

    public IgRetryableException(String message, int httpStatus, @Nullable String errorCode) {
        super(message, httpStatus, errorCode);
    }

    @Override
    public IgErrorClass errorClass() {
        return IgErrorClass.RETRYABLE;
    }
}
