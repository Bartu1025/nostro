package nostro.recon;

import nostro.core.Ccy;
import nostro.core.Command;
import nostro.core.Command.RailOutcome;
import nostro.core.Command.Source;
import nostro.core.Event.Kind;
import nostro.core.State;
import nostro.core.Transfer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.TreeMap;

/**
 * Compares what the ledger believes left the nostro account with what the bank's statement says did.
 * Pure: returns a classified report plus the commands that would resolve it. Applying them is the caller's call.
 *
 * Shaped like the daily internal reconciliation an EMI runs between client-money records and the safeguarding
 * account. It is not a compliance implementation; it is the mechanism one would be built on.
 */
public final class Recon {
    private Recon() {}

    public enum Class_ { MATCHED, TIMING, RESOLVES_UNKNOWN, TRUE_BREAK }

    public record Line(String ref, long amount, Ccy ccy) {}

    public record Item(Class_ cls, String ref, Ccy ccy, long ledger, long statement, String note) {}

    public record Report(List<Item> items, List<Command> resolutions, long asOf) {
        public boolean pass() { return items.stream().noneMatch(i -> i.cls == Class_.TRUE_BREAK); }

        public EnumMap<Class_, Integer> counts() {
            EnumMap<Class_, Integer> m = new EnumMap<>(Class_.class);
            for (Class_ c : Class_.values()) m.put(c, 0);
            for (Item i : items) m.merge(i.cls, 1, Integer::sum);
            return m;
        }

        public String render() { return render(Integer.MAX_VALUE); }

        public String render(int maxItems) {
            EnumMap<Class_, Integer> c = counts();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("RECON as-of=%d  MATCHED %d  TIMING %d  RESOLVES_UNKNOWN %d  TRUE_BREAK %d%n",
                asOf, c.get(Class_.MATCHED), c.get(Class_.TIMING), c.get(Class_.RESOLVES_UNKNOWN), c.get(Class_.TRUE_BREAK)));
            int shown = 0, hidden = 0;
            for (Item i : items)
                if (i.cls != Class_.MATCHED && shown++ >= maxItems) hidden++;
                else if (i.cls != Class_.MATCHED)
                    sb.append(String.format("  %-16s %-14s %s ledger=%s statement=%s  %s%n", i.cls, i.ref, i.ccy,
                        i.ledger < 0 ? "-" : i.ccy.fmt(i.ledger), i.statement < 0 ? "-" : i.ccy.fmt(i.statement), i.note));
            if (hidden > 0) sb.append("  ... ").append(hidden).append(" more\n");
            sb.append("  status: ").append(pass() ? (c.get(Class_.TIMING) > 0 ? "PASS (timing-only)" : "PASS") : "BREAK");
            if (!resolutions.isEmpty()) sb.append("   resolutions: ").append(resolutions.size());
            return sb.append('\n').toString();
        }
    }

    /**
     * @param asOf the statement is complete for payouts dispatched up to this time
     * @param lag  settlement lag: a payout newer than asOf - lag may legitimately be missing (TIMING, not a break)
     */
    public static Report run(State s, List<Line> statement, long asOf, long lag) {
        TreeMap<String, Line> byRef = new TreeMap<>();
        for (Line l : statement) byRef.put(l.ref, l);
        List<Item> items = new ArrayList<>();
        List<Command> fixes = new ArrayList<>();

        for (Transfer t : s.transfers.values()) {
            if (t.kind != Kind.PAYOUT) continue;
            Line l = byRef.remove(t.id.v());
            boolean inWindow = t.createdAt > asOf - lag;
            switch (t.payout) {
                case SETTLED -> {
                    if (l == null) items.add(inWindow ? item(Class_.TIMING, t, -1, "settled, not yet on statement")
                                                      : item(Class_.TRUE_BREAK, t, -1, "settled in ledger, absent from statement"));
                    else if (l.amount != t.postedAmount || l.ccy != t.ccy) items.add(item(Class_.TRUE_BREAK, t, l.amount, "amount mismatch"));
                    else items.add(item(Class_.MATCHED, t, l.amount, ""));
                }
                case FAILED -> {
                    if (l != null) items.add(item(Class_.TRUE_BREAK, t, l.amount, "failed in ledger but bank debited"));
                }
                case UNKNOWN -> {
                    if (l != null) {
                        if (l.amount == t.amount && l.ccy == t.ccy) {
                            items.add(item(Class_.RESOLVES_UNKNOWN, t, l.amount, "on statement -> SETTLED"));
                            fixes.add(new Command.RailResult(t.id, RailOutcome.OK, Source.RECON));
                        } else items.add(item(Class_.TRUE_BREAK, t, l.amount, "unknown outcome and amount mismatch"));
                    } else if (inWindow) items.add(item(Class_.TIMING, t, -1, "unknown, still inside settlement window"));
                    else {
                        items.add(item(Class_.RESOLVES_UNKNOWN, t, -1, "absent past window -> FAILED, hold released"));
                        fixes.add(new Command.RailResult(t.id, RailOutcome.FAILED, Source.RECON));
                    }
                }
                case SUBMITTED, DISPATCHED -> {
                    if (l != null) items.add(item(Class_.TRUE_BREAK, t, l.amount, "bank debited a payout we have no result for"));
                    else items.add(item(Class_.TIMING, t, -1, "in flight"));
                }
            }
        }
        for (Line l : byRef.values())
            items.add(new Item(Class_.TRUE_BREAK, l.ref, l.ccy, -1, l.amount, "on statement, not in ledger"));
        return new Report(items, fixes, asOf);
    }

    private static Item item(Class_ c, Transfer t, long statement, String note) {
        return new Item(c, t.id.v(), t.ccy, t.payout == nostro.core.Event.Payout.SETTLED ? t.postedAmount : t.amount, statement, note);
    }

    /** Parses "ref,amount_minor,ccy" with a header row. Amounts are integers in minor units; no decimal parsing anywhere. */
    public static List<Line> parseCsv(String csv) {
        List<Line> out = new ArrayList<>();
        String[] rows = csv.split("\n");
        for (int i = 1; i < rows.length; i++) {
            String r = rows[i].trim();
            if (r.isEmpty()) continue;
            String[] f = r.split(",");
            if (f.length != 3) throw new IllegalArgumentException("bad statement row " + (i + 1) + ": " + r);
            out.add(new Line(f[0].trim(), Long.parseLong(f[1].trim()), Ccy.valueOf(f[2].trim())));
        }
        return out;
    }
}
