package dev.amfshr.tradebench.ig.error;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Classifies IG error codes into the §1.6 taxonomy. Matching is case-insensitive by
 * family/substring because IG varies suffixes by field (e.g. {@code validation.pattern.
 * invalid.authenticationRequest.identifier}).
 *
 * <p>Unknown codes default to RETRYABLE: the supervisor bounds retries at run time (its
 * recovery budget, §3.2) and at boot (the same budget), so a misclassified retryable is
 * bounded — while a misclassified fatal permanently stops capture on a transient, the worse
 * failure for a data service. Callers must never loop on RETRYABLE unbounded.
 */
public final class IgErrorClassifier {

    private static final List<String> FATAL_FAMILIES = List.of(
            "error.security.api-key",
            "error.security.invalid-details", // wrong identifier/password — retrying risks the lockout
            "preferred.account.disabled",
            "preferred.account.not.set",
            "error.security.account-suspended",
            "error.switch.invalid-accountid",
            "failure.missing.credentials",
            "failure.kyc.required");

    private IgErrorClassifier() {
    }

    public static IgErrorClass classify(@Nullable String errorCode) {
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
