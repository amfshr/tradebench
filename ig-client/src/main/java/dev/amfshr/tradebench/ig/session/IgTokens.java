package dev.amfshr.tradebench.ig.session;

/**
 * The v2 session token pair, read from the CST / X-SECURITY-TOKEN response headers. Tokens
 * are bound to the active account and change on every account switch (§1.1/§1.3) — never
 * cache them across a switch.
 */
public record IgTokens(String cst, String securityToken) {

    /** Lightstreamer password wire format (§1.3): streaming has no login of its own. */
    public String lightstreamerPassword() {
        return "CST-" + cst + "|XST-" + securityToken;
    }

    @Override
    public String toString() {
        return "IgTokens[cst=***, securityToken=***]";
    }
}
