package nostro;

import nostro.core.Canon;
import nostro.core.Event;
import nostro.core.Event.Kind;
import nostro.core.Ids.AccountId;
import nostro.core.State;
import nostro.core.Transfer;
import nostro.store.Store;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Read-only projections over the log or the live state. None of this is in the core. */
public final class Views {
    private Views() {}

    /** Evidence trail: every event that touched the account up to atSeq, with the running balance after each. */
    public static String explainBalance(Store store, AccountId acct, long atSeq) {
        State s = new State();
        StringBuilder out = new StringBuilder();
        for (Store.Row r : store.read(1, Math.min(atSeq, store.lastSeq()))) {
            Event e = Canon.decode(r.line());
            Transfer t = switch (e) {
                case Event.TransferPending p -> null;
                case Event.TransferPosted p -> s.transfers.get(p.id());
                case Event.TransferVoided v -> s.transfers.get(v.id());
                default -> null;
            };
            boolean touches = switch (e) {
                case Event.AccountOpened a -> a.id().equals(acct);
                case Event.TransferPending p -> p.debit().equals(acct) || p.credit().equals(acct);
                case Event.PayoutStatus p -> false;
                default -> t != null && (t.debit.equals(acct) || t.credit.equals(acct));
            };
            s.evolve(e);
            if (!touches) continue;
            var a = s.accounts.get(acct);
            String what = switch (e) {
                case Event.AccountOpened x -> "opened " + x.ccy() + (x.allowOverdraft() ? " (house)" : "");
                case Event.TransferPending p -> (p.debit().equals(acct) ? "hold  -" : "hold  +") + a.ccy.fmt(p.amount()) + "  " + p.kind() + " " + p.id() + (p.ref().isEmpty() ? "" : "  " + p.ref());
                case Event.TransferPosted p -> (t.debit.equals(acct) ? "post  -" : "post  +") + a.ccy.fmt(p.amount()) + "  " + t.id;
                case Event.TransferVoided v -> "void   " + a.ccy.fmt(t.amount) + "  " + t.id + " (" + v.reason() + ")";
                default -> "";
            };
            out.append(String.format("  #%-7d %-44s balance=%s available=%s%n", r.seq(), what, a.ccy.fmt(a.balance()), a.ccy.fmt(a.available())));
        }
        var a = s.accounts.get(acct);
        if (a == null) return "  (no such account at seq " + atSeq + ")\n";
        out.append(String.format("  %s @ seq %d: balance=%s available=%s pending_debits=%s pending_credits=%s%n",
            acct, s.seq, a.ccy.fmt(a.balance()), a.ccy.fmt(a.available()), a.ccy.fmt(a.debitsPending), a.ccy.fmt(a.creditsPending)));
        return out.toString();
    }

    /** What an on-call engineer actually looks at: everything non-terminal, grouped by state, oldest first. */
    public static String stuck(State s, long now, int limit) {
        TreeMap<String, List<Transfer>> groups = new TreeMap<>();
        for (Transfer t : s.transfers.values()) {
            if (t.status != Transfer.Status.PENDING) continue;
            String g = t.kind == Kind.PAYOUT ? "PAYOUT/" + t.payout : "HOLD";
            groups.computeIfAbsent(g, k -> new ArrayList<>()).add(t);
        }
        StringBuilder out = new StringBuilder();
        if (groups.isEmpty()) return "  nothing open\n";
        groups.forEach((g, list) -> {
            list.sort((x, y) -> Long.compare(x.createdAt, y.createdAt));
            out.append(String.format("  %-16s %d open, oldest %s%n", g, list.size(), age(now - list.get(0).createdAt)));
            for (int i = 0; i < Math.min(limit, list.size()); i++) {
                Transfer t = list.get(i);
                out.append(String.format("    %-14s %s %12s  age %s%n", t.id, t.ccy, t.ccy.fmt(t.amount), age(now - t.createdAt)));
            }
        });
        return out.toString();
    }

    static String age(long micros) {
        long s = micros / 1_000_000;
        return s < 60 ? s + "s" : s < 3600 ? s / 60 + "m" : s < 86400 ? s / 3600 + "h" : s / 86400 + "d";
    }
}
