package dev.amfshr.tradebench.ig.error;

/**
 * Base for typed IG API failures. Carries the HTTP status and IG error code so callers can
 * audit the raw facts; the subclass encodes the taxonomy verdict.
 */
public abstract class IgApiException extends RuntimeException {

    private final int httpStatus;
    private final String errorCode;

    protected IgApiException(String message, int httpStatus, String errorCode) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** IG's {@code errorCode} body field; may be null when the response carried none. */
    public String errorCode() {
        return errorCode;
    }

    public abstract IgErrorClass errorClass();
}
