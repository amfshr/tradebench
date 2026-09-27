package dev.amfshr.tradebench.ig.error;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Anchors: the §1.6 taxonomy families, typed out from the playbook — not from the code. */
class IgErrorClassifierTest {

    @ParameterizedTest
    @CsvSource({
            // fatal config: retry can never fix these; retrying risks a key lockout
            "error.security.api-key-invalid, FATAL_CONFIG",
            "error.security.api-key-disabled, FATAL_CONFIG",
            "error.public-api.failure.preferred.account.disabled, FATAL_CONFIG",
            "error.public-api.failure.preferred.account.not.set, FATAL_CONFIG",
            "error.security.account-suspended, FATAL_CONFIG",
            "error.switch.invalid-accountId, FATAL_CONFIG",
            "validation.pattern.invalid.authenticationRequest.identifier, FATAL_CONFIG",
            "validation.null-not-allowed.request.accountId, FATAL_CONFIG",
            // retryable: a rebuild/backoff fixes these
            "error.security.account-token-invalid, RETRYABLE",
            "error.security.account-token-missing, RETRYABLE",
            "error.security.client-token-invalid, RETRYABLE",
            "error.public-api.exceeded-api-key-allowance, RETRYABLE",
            "error.public-api.exceeded-account-allowance, RETRYABLE",
            "system.error, RETRYABLE",
            "get.session.timeout, RETRYABLE",
    })
    void classifiesTheTaxonomyFamilies(String code, IgErrorClass expected) {
        assertEquals(expected, IgErrorClassifier.classify(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ERROR.SECURITY.API-KEY-INVALID",
            "Validation.Pattern.Invalid.Something.Else",
            "ERROR.SWITCH.INVALID-ACCOUNTID",
    })
    void matchingIsCaseInsensitive(String shoutyCode) {
        assertEquals(IgErrorClass.FATAL_CONFIG, IgErrorClassifier.classify(shoutyCode));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"error.some-new-code-ig-invented", "unclassifiable nonsense"})
    void unknownCodesDefaultToRetryable(String code) {
        // Bounded by the backoff ladder's ceiling; a wrong FATAL would stop capture forever.
        assertEquals(IgErrorClass.RETRYABLE, IgErrorClassifier.classify(code));
    }
}
