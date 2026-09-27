package dev.amfshr.tradebench.ig.http;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One HTTP response. Header lookup is case-insensitive because HTTP/2 lowercases header
 * names — IG's CST / X-SECURITY-TOKEN tokens arrive as response headers (§1.3), so a
 * case-sensitive read would lose the session.
 */
public record HttpResult(int status, Map<String, List<String>> headers, String body) {

    public Optional<String> firstHeader(String name) {
        return headers.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().equalsIgnoreCase(name))
                .flatMap(e -> e.getValue().stream())
                .findFirst();
    }
}
