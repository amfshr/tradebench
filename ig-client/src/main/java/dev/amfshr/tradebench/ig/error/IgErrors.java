package dev.amfshr.tradebench.ig.error;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.ig.http.HttpResult;

/** Maps a failed IG response to the typed taxonomy — one implementation for every caller. */
public final class IgErrors {

    private IgErrors() {
    }

    /** IG's {@code errorCode} body field, or null when the body has none / isn't JSON. */
    public static @Nullable String errorCode(ObjectMapper mapper, HttpResult result) {
        try {
            JsonNode node = mapper.readTree(result.body());
            return node.hasNonNull("errorCode") ? node.get("errorCode").asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static IgApiException from(ObjectMapper mapper, HttpResult result, String operation) {
        String code = errorCode(mapper, result);
        String message = operation + " failed with HTTP " + result.status()
                + (code != null ? " (" + code + ")" : "");
        if (IgErrorClassifier.classify(code) == IgErrorClass.FATAL_CONFIG) {
            return new IgFatalConfigException(message, result.status(), code);
        }
        return new IgRetryableException(message, result.status(), code);
    }
}
