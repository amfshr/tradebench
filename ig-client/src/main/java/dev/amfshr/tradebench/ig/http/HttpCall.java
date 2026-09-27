package dev.amfshr.tradebench.ig.http;

import java.net.URI;
import java.util.Map;

/** One HTTP request, transport-agnostic. Body may be null for GET/DELETE. */
public record HttpCall(String method, URI uri, Map<String, String> headers, String body) {
}
