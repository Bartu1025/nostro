package nostro;

import nostro.core.Ccy;
import nostro.core.Command;
import nostro.core.Command.RailOutcome;
import nostro.core.Command.Source;
import nostro.core.Event.Payout;
import nostro.core.Ids.AccountId;
import nostro.core.Ids.TransferId;
import nostro.core.Outcome;
import nostro.core.State;
import nostro.core.Transfer;
import nostro.rail.FakeRail;
import nostro.recon.Recon;
import nostro.store.SqliteStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("demo")) { demo(); return; }
        if (args.length < 2) { usage(); return; }
        String db = args[0], cmd = args[1];
        String[] a = Arrays.copyOfRange(args, 2, args.length);
        long now = System.currentTimeMillis() * 1000; // the shell owns the clock; the core never reads one

        try (SqliteStore store = new SqliteStore(db)) {
            if (cmd.equals("verify-chain")) {
                long bad = Engine.verifyChain(store);
                System.out.println(bad < 0 ? "chain OK through seq " + store.lastSeq() : "BREAK at seq " + bad);
                return;
            }
            if (cmd.equals("explain")) {
                System.out.print(Views.explainBalance(store, new AccountId(a[0]), a.length > 1 ? Long.parseLong(a[1]) : Long.MAX_VALUE));
                return;
            }
            Engine e = new Engine(store, (id, amount, ccy, ref) -> { throw new IllegalStateException("no rail configured; use 'rail <id> OK|FAILED|TIMEOUT'"); });
            State s = e.state;
            switch (cmd) {
                case "open" -> show(e.apply(new Command.OpenAccount(new AccountId(a[0]), Ccy.valueOf(a[1]), a.length > 2 && a[2].equals("house")), now));
                case "transfer" -> show(e.apply(new Command.Transfer(new TransferId(a[0]), new AccountId(a[1]), new AccountId(a[2]), Long.parseLong(a[3]), opt(a, 4)), now));
                case "reserve" -> show(e.apply(new Command.Reserve(new TransferId(a[0]), new AccountId(a[1]), new AccountId(a[2]), Long.parseLong(a[3]), Long.parseLong(a[4]), opt(a, 5)), now));
                case "post" -> show(e.apply(new Command.Post(new TransferId(a[0]), Long.parseLong(a[1])), now));
                case "cancel" -> show(e.apply(new Command.Cancel(new TransferId(a[0])), now));
                case "payout" -> show(e.apply(new Command.SubmitPayout(new TransferId(a[0]), new AccountId(a[1]), new AccountId(a[2]), Long.parseLong(a[3]), opt(a, 4)), now));
                case "rail" -> {
                    TransferId id = new TransferId(a[0]);
                    Transfer t = s.transfers.get(id);
                    if (t != null && t.payout == Payout.SUBMITTED) e.apply(new Command.MarkDispatched(id), now);
                    show(e.apply(new Command.RailResult(id, RailOutcome.valueOf(a[1]), Source.RAIL), now));
                }
                case "return" -> show(e.apply(new Command.Return(new TransferId(a[0]), new TransferId(a[1]), Long.parseLong(a[2]), opt(a, 3)), now));
                case "tick" -> show(e.apply(new Command.Tick(), now));
                case "balance" -> {
                    var acc = s.accounts.get(new AccountId(a[0]));
                    if (acc == null) System.out.println("no such account");
                    else System.out.printf("%s %s balance=%s available=%s pending_debits=%s pending_credits=%s%n", acc.id, acc.ccy,
                        acc.ccy.fmt(acc.balance()), acc.ccy.fmt(acc.available()), acc.ccy.fmt(acc.debitsPending), acc.ccy.fmt(acc.creditsPending));
                }
                case "stuck" -> System.out.print(Views.stuck(s, now, 10));
                case "replay" -> { s.checkInvariants(); System.out.println("seq=" + s.seq + " state_hash=" + s.hash() + " invariants OK"); }
                case "recon" -> {
                    Recon.Report r = Recon.run(s, Recon.parseCsv(Files.readString(Path.of(a[0]))), now, a.length > 1 ? Long.parseLong(a[1]) : 0);
                    System.out.print(r.render());
                    if (a.length > 2 && a[2].equals("--apply")) for (Command c : r.resolutions()) System.out.println("  applied " + c + " -> " + e.apply(c, now));
                }
                default -> usage();
            }
        }
    }

    static String opt(String[] a, int i) { return a.length > i ? a[i] : ""; }

    static void show(Outcome o) {
        System.out.println(switch (o) {
            case Outcome.Applied x -> "applied " + x.events().size() + " event(s)";
            case Outcome.Replayed x -> "replayed (already applied with the same payload)";
            case Outcome.Rejected x -> "rejected " + x.reason() + ": " + x.detail();
        });
    }

    static void usage() {
        System.out.println("""
            nostro demo
            nostro <db> open <acct> <GBP|EUR|USD|JPY|KWD> [house]
            nostro <db> transfer <id> <debit> <credit> <amount_minor> [ref]
            nostro <db> reserve <id> <debit> <credit> <amount_minor> <expiresAtMicros|0> [ref]
            nostro <db> post <id> <amount_minor> | cancel <id>
            nostro <db> payout <id> <client> <nostro> <amount_minor> [ref]
            nostro <db> rail <id> OK|FAILED|TIMEOUT
            nostro <db> return <id> <originalPayoutId> <amount_minor> [ref]
            nostro <db> tick | balance <acct> | stuck | replay | verify-chain
            nostro <db> explain <acct> [atSeq]
            nostro <db> recon <statement.csv> [lagMicros] [--apply]""");
    }

    // ---------------------------------------------------------------------------------------------------------------

    static void demo() throws Exception {
        Path db = Files.createTempFile("nostro-demo", ".db");
        Files.delete(db);
        long seed = 42;
        int ops = 10_000;
        FakeRail rail = new FakeRail(seed + 1, 0.05, 0.10);

        System.out.printf("nostro demo  seed=%d  db=%s%n%n", seed, db);

        SqliteStore store = new SqliteStore(db.toString());
        Engine e = new Engine(store, rail);
        Simulation sim = new Simulation(seed, e, rail);
        sim.setup();
        sim.steps(ops);
        e.state.checkInvariants();
        String h1 = e.state.hash();
        long payouts = e.state.transfers.values().stream().filter(t -> t.kind == nostro.core.Event.Kind.PAYOUT).count();
        long unknown = e.state.transfers.values().stream().filter(t -> t.payout == Payout.UNKNOWN).count();
        System.out.printf("[1] workload   %,d commands: %d client accounts in 4 currencies, holds, FX, %d payouts via a rail that times out 10%% of the time%n", ops, 40, payouts);
        System.out.printf("    applied %,d  rejected %,d  events %,d  (seq %,d)%n", sim.applied.size(), sim.rejected, sim.events, e.state.seq);
        System.out.printf("    invariants  OK  (per-currency debits == credits, no client overdraft, open holds == pending counters)%n");
        System.out.printf("    state_hash  %s%n%n", h1);

        Engine replayed = new Engine(new SqliteStore(db.toString()), rail);
        String h2 = replayed.state.hash();
        System.out.printf("[2] replay     fresh process, same SQLite file, fold every event, verify chain while reading%n");
        System.out.printf("    state_hash  %s   %s%n%n", h2, h1.equals(h2) ? "identical" : "DIFFERENT - determinism broken");
        if (!h1.equals(h2)) System.exit(1);

        AccountId victim = e.state.accounts.keySet().stream().filter(k -> k.v().startsWith("c") && k.v().endsWith("GBP")).findFirst().orElseThrow();
        TransferId crashId = new TransferId("pay_crash");
        e.apply(new Command.SubmitPayout(crashId, victim, Simulation.nostro(Ccy.GBP), 125_00, "withdrawal"), sim.now);
        rail.crashAfterNextSend();
        String died;
        try { e.dispatch(sim.now); died = "did not crash?"; } catch (IllegalStateException ex) { died = ex.getMessage(); }
        store.close();
        System.out.printf("[3] crash      submitted %s, logged DISPATCHED, called the rail - the bank moved the money - then: %s%n", crashId, died);
        store = new SqliteStore(db.toString());
        e = new Engine(store, rail);
        int recovered = e.recover(sim.now);
        System.out.printf("    recovery   restarted: %d payout(s) were DISPATCHED with no result -> UNKNOWN. Not re-sent: we may already have paid.%n", recovered);
        System.out.printf("    stuck view (what on-call sees):%n%s%n", Views.stuck(e.state, sim.now, 3));

        String csv = rail.statementCsv();
        FakeRail.Line first = rail.processed.get(0);
        csv = csv.replace(first.ref() + "," + first.amount() + ",", first.ref() + "," + (first.amount() + 1) + ",") + "BANK-FEE-0001,250,GBP\n";
        long lag = 2 * 3600_000_000L;
        Recon.Report r = Recon.run(e.state, Recon.parseCsv(csv), sim.now, lag);
        System.out.printf("[4] recon      bank statement: %d lines (+1 planted amount mismatch, +1 planted unexpected debit), 2h settlement window%n", rail.processed.size() + 1);
        System.out.print(indent(r.render(14)));
        int applied = 0;
        for (Command c : r.resolutions()) if (e.apply(c, sim.now) instanceof Outcome.Applied) applied++;
        e.state.checkInvariants();
        System.out.printf("    applied %d resolution(s): %s -> %s, %d UNKNOWN left (inside window); invariants OK; state_hash %s%n%n",
            applied, crashId, e.state.transfers.get(crashId).payout,
            e.state.transfers.values().stream().filter(t -> t.payout == Payout.UNKNOWN).count(), e.state.hash());

        long seq = e.state.seq / 2;
        String line = store.read(seq, seq).get(0).line();
        String tampered = line.replaceFirst("\\|(\\d+)\\|", "|$1" + "0|"); // one extra zero on the first numeric field
        store.tamper(seq, tampered);
        long bad = Engine.verifyChain(store);
        String load;
        try { new Engine(store, rail); load = "loaded?!"; } catch (IllegalStateException ex) { load = "refused: " + ex.getMessage(); }
        System.out.printf("[5] tamper     edited event #%d in the SQLite file directly: %s%n", seq, tampered.length() > 70 ? tampered.substring(0, 70) + "..." : tampered);
        System.out.printf("    verify-chain  BREAK at seq %d%n", bad);
        System.out.printf("    engine open   %s%n", load);
        store.close();
    }

    static String indent(String s) {
        StringBuilder sb = new StringBuilder();
        for (String l : s.split("\n")) sb.append("    ").append(l).append('\n');
        return sb.toString();
    }
}
