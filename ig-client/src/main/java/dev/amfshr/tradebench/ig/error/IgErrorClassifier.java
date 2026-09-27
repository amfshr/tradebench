package dev.amfshr.tradebench.ig.error;

import java.util.List;
import java.util.Locale;

/**
 * Classifies IG error codes into the §1.6 taxonomy. Matching is case-insensitive by
 * family/substring because IG varies suffixes by field (e.g. {@code validation.pattern.
 * invalid.authenticationRequest.identifier}).
 *
 * <p>Unknown codes default to RETRYABLE: T5's supervisor ladder will bound retries (floor,
 * ceiling, 10-consecutive-failure stop — §3.2), so a misclassified retryable is bounded —
 * while a misclassified fatal permanently stops capture on a transient, the worse failure
 * for a data service. Until that ladder exists, callers must not loop on RETRYABLE
 * unbounded.
 */
public final class IgErrorClassifier {

    private static final List<String> FATAL_FAMILIES = List.of(
            "error.security.api-key",
            "preferred.account.disabled",
            "preferred.account.not.set",
            "error.security.account-suspended",
            "error.switch.invalid-accountid",
            "failure.missing.credentials");

    private IgErrorClassifier() {
    }

    public static IgErrorClass classify(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return IgErrorClass.RETRYABLE;
        }
        String code = errorCode.toLowerCase(Locale.ROOT).trim();
        if (code.startsWith("validation")) {
            return IgErrorClass.FATAL_CONFIG;
        }
        for (String family : FATAL_FAMILIES) {
            if (code.contains(family)) {
                return IgErrorClass.FATAL_CONFIG;
            }
        }
        return IgErrorClass.RETRYABLE;
    }
}
