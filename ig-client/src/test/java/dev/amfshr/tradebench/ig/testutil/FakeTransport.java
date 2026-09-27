package dev.amfshr.tradebench.ig.testutil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import dev.amfshr.tradebench.ig.http.HttpCall;
import dev.amfshr.tradebench.ig.http.HttpResult;
import dev.amfshr.tradebench.ig.http.HttpTransport;

/** Scripted transport: enqueue responses, capture every call for wire-contract assertions. */
public final class FakeTransport implements HttpTransport {

    private final Deque<HttpResult> scripted = new ArrayDeque<>();
    public final List<HttpCall> calls = new ArrayList<>();

    public FakeTransport enqueue(HttpResult result) {
        scripted.addLast(result);
        return this;
    }

    public static HttpResult json(int status, String body) {
        return new HttpResult(status, Map.of(), body);
    }

    /**
     * Response carrying session tokens. Header names deliberately lowercase — HTTP/2
     * lowercases on the wire, so consuming code must read them case-insensitively.
     */
    public static HttpResult jsonWithTokens(int status, String body, String cst, String xst) {
        return new HttpResult(status,
                Map.of("cst", List.of(cst), "x-security-token", List.of(xst)), body);
    }

    @Override
    public HttpResult exchange(HttpCall call) {
        calls.add(call);
        if (scripted.isEmpty()) {
            throw new AssertionError("FakeTransport: unexpected call " + call.method() + " "
                    + call.uri());
        }
        return scripted.removeFirst();
    }
}
