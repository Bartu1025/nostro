package nostro.core;

import nostro.core.Event.Kind;
import nostro.core.Ids.AccountId;
import nostro.core.Ids.TransferId;
import nostro.core.Transfer.Status;

import java.util.EnumMap;
import java.util.TreeMap;

/**
 * In-memory projection of the event log. TreeMaps on purpose: iteration order is part of the state hash,
 * and HashMap order is not a contract (nor is Map.of's - it is salted per JVM run).
 */
public final class State {
    public final TreeMap<AccountId, Account> accounts = new TreeMap<>();
    public final TreeMap<TransferId, Transfer> transfers = new TreeMap<>();
    /** Sequence number of the last event folded in. */
    public long seq;

    /** The only mutator. Called for every event exactly once, in log order, on the live path and on replay alike. */
    public void evolve(Event e) {
        seq++;
        switch (e) {
            case Event.AccountOpened a -> accounts.put(a.id(), new Account(a.id(), a.ccy(), a.allowOverdraft()));
            case Event.TransferPending p -> {
                Transfer t = new Transfer(p);
                transfers.put(t.id, t);
                Account d = accounts.get(t.debit), c = accounts.get(t.credit);
                d.debitsPending = Math.addExact(d.debitsPending, t.amount);
                c.creditsPending = Math.addExact(c.creditsPending, t.amount);
            }
            case Event.TransferPosted p -> {
                Transfer t = transfers.get(p.id());
                Account d = accounts.get(t.debit), c = accounts.get(t.credit);
                d.debitsPending = Math.subtractExact(d.debitsPending, t.amount);
                d.debitsPosted = Math.addExact(d.debitsPosted, p.amount());
                c.creditsPending = Math.subtractExact(c.creditsPending, t.amount);
                c.creditsPosted = Math.addExact(c.creditsPosted, p.amount());
                t.status = Status.POSTED;
                t.postedAmount = p.amount();
                if (t.kind == Kind.RETURN) {
                    Transfer o = transfers.get(t.original);
                    o.returnedTotal = Math.addExact(o.returnedTotal, p.amount());
                }
            }
            case Event.TransferVoided v -> {
                Transfer t = transfers.get(v.id());
                Account d = accounts.get(t.debit), c = accounts.get(t.credit);
                d.debitsPending = Math.subtractExact(d.debitsPending, t.amount);
                c.creditsPending = Math.subtractExact(c.creditsPending, t.amount);
                t.status = Status.VOIDED;
                t.voidReason = v.reason();
            }
            case Event.PayoutStatus s -> transfers.get(s.id()).payout = s.status();
        }
    }

    /** SHA-256 over a canonical dump of every account and transfer. Two states with the same hash are the same state. */
    public String hash() {
        StringBuilder sb = new StringBuilder();
        sb.append("seq|").append(seq).append('\n');
        for (Account a : accounts.values())
            sb.append(Canon.join("A", a.id, a.ccy, a.allowOverdraft, a.debitsPending, a.debitsPosted, a.creditsPending, a.creditsPosted)).append('\n');
        for (Transfer t : transfers.values())
            sb.append(Canon.join("T", t.id, t.debit, t.credit, t.amount, t.ccy, t.expiresAt, t.linkId, t.kind, t.ref, t.original,
                t.createdAt, t.status, t.postedAmount, t.voidReason, t.payout, t.returnedTotal)).append('\n');
        return Canon.sha256Hex(sb.toString());
    }

    /**
     * Throws on the first violated invariant. O(accounts + transfers), so: after every command in tests and the simulator,
     * at checkpoints in a real deployment. The per-command O(delta) checks are the Math.*Exact calls in evolve().
     */
    public void checkInvariants() {
        EnumMap<Ccy, long[]> sums = new EnumMap<>(Ccy.class); // debitsPending, debitsPosted, creditsPending, creditsPosted
        for (Account a : accounts.values()) {
            require(a.debitsPending >= 0 && a.debitsPosted >= 0 && a.creditsPending >= 0 && a.creditsPosted >= 0, "negative counter on " + a.id);
            require(a.allowOverdraft || a.available() >= 0, "overdraft on " + a.id + ": available=" + a.available());
            long[] s = sums.computeIfAbsent(a.ccy, k -> new long[4]);
            s[0] = Math.addExact(s[0], a.debitsPending);
            s[1] = Math.addExact(s[1], a.debitsPosted);
            s[2] = Math.addExact(s[2], a.creditsPending);
            s[3] = Math.addExact(s[3], a.creditsPosted);
        }
        sums.forEach((ccy, s) -> {
            require(s[0] == s[2], "pending debits " + s[0] + " != pending credits " + s[2] + " in " + ccy);
            require(s[1] == s[3], "posted debits " + s[1] + " != posted credits " + s[3] + " in " + ccy);
        });

        // Holds reconcile against the ledger: the sum of open transfers on each account equals its pending counters.
        TreeMap<AccountId, long[]> held = new TreeMap<>();
        for (Transfer t : transfers.values()) {
            require(t.amount > 0, "non-positive amount on " + t.id);
            require(t.postedAmount <= t.amount, "posted more than held on " + t.id);
            require(t.ccy == accounts.get(t.debit).ccy && t.ccy == accounts.get(t.credit).ccy, "currency mismatch on " + t.id);
            if (t.status == Status.PENDING) {
                held.computeIfAbsent(t.debit, k -> new long[2])[0] += t.amount;
                held.computeIfAbsent(t.credit, k -> new long[2])[1] += t.amount;
            }
            if (t.kind == Kind.PAYOUT) {
                require(t.payout != null, "payout without status " + t.id);
                switch (t.payout) {
                    case SETTLED -> require(t.status == Status.POSTED, "SETTLED payout not posted " + t.id);
                    case FAILED -> require(t.status == Status.VOIDED, "FAILED payout not voided " + t.id);
                    default -> require(t.status == Status.PENDING, "open payout not pending " + t.id);
                }
            } else {
                require(t.payout == null, "non-payout with payout status " + t.id);
            }
            if (t.kind == Kind.RETURN) {
                Transfer o = transfers.get(t.original);
                require(o != null && o.kind == Kind.PAYOUT, "return " + t.id + " against non-payout");
                require(o.returnedTotal <= o.postedAmount, "returns exceed original on " + o.id);
            }
        }
        for (Account a : accounts.values()) {
            long[] h = held.getOrDefault(a.id, new long[2]);
            require(h[0] == a.debitsPending, "open holds " + h[0] + " != debitsPending " + a.debitsPending + " on " + a.id);
            require(h[1] == a.creditsPending, "open holds " + h[1] + " != creditsPending " + a.creditsPending + " on " + a.id);
        }
    }

    private static void require(boolean ok, String msg) {
        if (!ok) throw new IllegalStateException("invariant violated: " + msg);
    }
}
