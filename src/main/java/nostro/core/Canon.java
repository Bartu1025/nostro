package nostro.core;

import nostro.core.Command.Source;
import nostro.core.Event.Kind;
import nostro.core.Event.Payout;
import nostro.core.Event.VoidReason;
import nostro.core.Ids.AccountId;
import nostro.core.Ids.TransferId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Canonical one-line encoding of events: TYPE|field|field|... with fields in declared order, null as "".
 * Hand-written on purpose: a reflection-based serializer's field order is not a stable contract, and the
 * hash chain is only as deterministic as these bytes.
 */
public final class Canon {
    private Canon() {}

    public static String encode(Event e) {
        return switch (e) {
            case Event.AccountOpened a -> join("ACC", a.id(), a.ccy(), a.allowOverdraft());
            case Event.TransferPending p -> join("PEND", p.id(), p.debit(), p.credit(), p.amount(), p.ccy(), p.expiresAt(),
                                                p.linkId(), p.kind(), p.ref(), p.original(), p.createdAt());
            case Event.TransferPosted p -> join("POST", p.id(), p.amount());
            case Event.TransferVoided v -> join("VOID", v.id(), v.reason());
            case Event.PayoutStatus s -> join("PAYOUT", s.id(), s.status(), s.source());
        };
    }

    public static Event decode(String line) {
        String[] f = line.split("\\|", -1);
        return switch (f[0]) {
            case "ACC" -> new Event.AccountOpened(new AccountId(f[1]), Ccy.valueOf(f[2]), Boolean.parseBoolean(f[3]));
            case "PEND" -> new Event.TransferPending(new TransferId(f[1]), new AccountId(f[2]), new AccountId(f[3]),
                Long.parseLong(f[4]), Ccy.valueOf(f[5]), Long.parseLong(f[6]), f[7].isEmpty() ? null : f[7], Kind.valueOf(f[8]),
                f[9], f[10].isEmpty() ? null : new TransferId(f[10]), Long.parseLong(f[11]));
            case "POST" -> new Event.TransferPosted(new TransferId(f[1]), Long.parseLong(f[2]));
            case "VOID" -> new Event.TransferVoided(new TransferId(f[1]), VoidReason.valueOf(f[2]));
            case "PAYOUT" -> new Event.PayoutStatus(new TransferId(f[1]), Payout.valueOf(f[2]), Source.valueOf(f[3]));
            default -> throw new IllegalArgumentException("unknown event type: " + f[0]);
        };
    }

    public static String join(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append('|');
            if (fields[i] != null) sb.append(fields[i]);
        }
        return sb.toString();
    }

    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Hash-chain link: h_n = H(h_{n-1} || line_n). Tamper-evident, not tamper-proof. */
    public static String chain(String prevHash, String line) { return sha256Hex(prevHash + "\n" + line); }

    public static final String GENESIS = "0".repeat(64);
}
