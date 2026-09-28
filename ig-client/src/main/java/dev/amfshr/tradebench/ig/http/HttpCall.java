package dev.amfshr.tradebench.ig.http;

import java.net.URI;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/** One HTTP request, transport-agnostic. Body is null for GET/DELETE. */
public record HttpCall(String method, URI uri, Map<String, String> headers, @Nullable String body) {
}
