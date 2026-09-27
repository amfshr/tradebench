package dev.amfshr.tradebench.ig.session;

/** One account on the profile, from the login response's {@code accounts[]}. */
public record IgAccount(String accountId, String accountType, boolean preferred) {
}
