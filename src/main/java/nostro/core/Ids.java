package nostro.core;

import java.util.regex.Pattern;

/** Identifiers are client-supplied. They ARE the idempotency keys: a duplicate id is a replay or a conflict, never a second entity. */
public final class Ids {
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9:_.\\-]{1,64}");

    private Ids() {}

    static String safe(String v, String what) {
        if (v == null || !SAFE.matcher(v).matches())
            throw new IllegalArgumentException(what + " must match " + SAFE.pattern() + ", got: " + v);
        return v;
    }

    /** Free-text reference. Must survive the canonical line format: no '|' and no newlines. */
    public static String ref(String v) {
        if (v == null) return "";
        if (v.length() > 140 || v.indexOf('|') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0)
            throw new IllegalArgumentException("ref must be <= 140 chars with no '|' or newline");
        return v;
    }

    public record AccountId(String v) implements Comparable<AccountId> {
        public AccountId { safe(v, "AccountId"); }
        @Override public int compareTo(AccountId o) { return v.compareTo(o.v); }
        @Override public String toString() { return v; }
    }

    public record TransferId(String v) implements Comparable<TransferId> {
        public TransferId { safe(v, "TransferId"); }
        @Override public int compareTo(TransferId o) { return v.compareTo(o.v); }
        @Override public String toString() { return v; }
    }
}
