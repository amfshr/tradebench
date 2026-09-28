package dev.amfshr.tradebench.ig.stream;

import dev.amfshr.tradebench.ig.session.IgSession;

/**
 * Opens the authenticated Lightstreamer session — §1.1 wiring: user = account id,
 * password = "CST-…|XST-…", endpoint from the login response, never hardcoded.
 */
public final class IgStreamClient {

    private final StreamTransport transport;

    public IgStreamClient(StreamTransport transport) {
        this.transport = transport;
    }

    public IgStreamSession connect(IgSession session, StreamEvents events,
            StreamTransport.ConnectionListener connectionListener) {
        StreamTransport.Connection connection = transport.connect(
                session.lightstreamerEndpoint(),
                session.activeAccountId(),
                session.tokens().lightstreamerPassword(),
                connectionListener);
        return new IgStreamSession(connection, session.activeAccountId(), events);
    }
}
