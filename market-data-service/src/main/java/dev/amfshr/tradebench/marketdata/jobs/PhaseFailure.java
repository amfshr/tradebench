package dev.amfshr.tradebench.marketdata.jobs;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A phase that failed part-way, carrying what it had done by then (E1-T16) — so a heal stopped by the
 * allowance reserve still records the points it spent and the allowance it last read. The failed row's
 * detail is this partial detail plus the error.
 */
public final class PhaseFailure extends Exception {

    private static final long serialVersionUID = 1L;

    private final ObjectNode partial;

    public PhaseFailure(ObjectNode partial, Exception cause) {
        super(cause.getMessage(), cause);
        this.partial = partial;
    }

    /** What the phase had done before it failed — a copy, so the row's detail can be added to safely. */
    public ObjectNode partial() {
        return partial.deepCopy();
    }
}
