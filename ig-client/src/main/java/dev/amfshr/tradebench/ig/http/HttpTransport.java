package dev.amfshr.tradebench.ig.http;

import java.io.IOException;

/**
 * The wire seam: everything above it is pure logic testable on fakes; the JDK implementation
 * is the only class that touches a real socket.
 */
public interface HttpTransport {

    HttpResult exchange(HttpCall call) throws IOException, InterruptedException;
}
