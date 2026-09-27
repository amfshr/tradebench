package dev.amfshr.tradebench.ig.testutil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads golden wire fixtures — the pinned bytes our parsing contract is tested against.
 * Provenance matters: see {@code resources/wire/README.md} for which fields are
 * playbook-anchored vs authored, and when to re-golden from captured bytes.
 */
public final class Wire {

    private Wire() {
    }

    public static String fixture(String name) {
        try (InputStream in = Wire.class.getResourceAsStream("/wire/" + name)) {
            if (in == null) {
                throw new AssertionError("missing wire fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("unreadable wire fixture: " + name, e);
        }
    }
}
