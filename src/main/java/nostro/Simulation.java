package nostro;

import nostro.core.Ccy;
import nostro.core.Command;
import nostro.core.Event.Kind;
import nostro.core.Event.Payout;
import nostro.core.Ids.AccountId;
import nostro.core.Ids.TransferId;
import nostro.core.Outcome;
import nostro.core.Transfer;
import nostro.rail.FakeRail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Seeded workload generator. java.util.Random's algorithm is specified, TreeMap order is total, and the core has no
 * ambient inputs - so the same seed yields the same commands, the same rejects, the same events and the same hash,
 * on any JVM on any OS. That is the whole determinism claim, and GoldenHashTest pins it.
 */
public final class Simulation {
    public static final long T0 = 1_760_000_000_000_000L; // 2025-10-09T08:53:20Z in micros; arbitrary but fixed
    public static final long STEP = 60_000_000L;           // clock advances up to a minute per command

    public final Engine engine;
    public final FakeRail rail;
    public final List<Command> applied = new ArrayList<>();
    public long now = T0;
    public int rejected, events;

    private final Random rnd;
    private final List<AccountId> clients = new ArrayList<>();
    private int n;

    public Simulation(long seed, Engine engine, FakeRail rail) {
        this.rnd = new Random(seed);
        this.engine = engine;
        this.rail = rail;
    }

    public static AccountId nostro(Ccy c) { return new AccountId("NOSTRO:" + c); }

    public static AccountId fx(Ccy c) { return new AccountId("FX:" + c); }

    /** House accounts per currency, 40 clients across 4 currencies, each funded by a deposit from the nostro. */
    public void setup() {
        for (Ccy c : new Ccy[]{Ccy.GBP, Ccy.EUR, Ccy.USD, Ccy.JPY}) {
            run(new Command.OpenAccount(nostro(c), c, true));
            run(new Command.OpenAccount(fx(c), c, true));
        }
        for (int i = 0; i < 40; i++) {
            Ccy c = new Ccy[]{Ccy.GBP, Ccy.EUR, Ccy.USD, Ccy.JPY}[i % 4];
            AccountId id = new AccountId(String.format(Locale.ROOT, "c%02d:%s", i, c));
            clients.add(id);
            run(new Command.OpenAccount(id, c, false));
            run(new Command.Transfer(tid("dep"), nostro(c), id, amount(c, 500_00 + rnd.nextInt(5_000_00)), "initial deposit"));
        }
    }

    public void steps(int count) { for (int i = 0; i < count; i++) step(); }

    public void step() {
        now += 1 + rnd.nextInt((int) STEP);
        int x = rnd.nextInt(100);
        if (x < 30) {
            AccountId a = client(), b = sameCcy(a);
            run(new Command.Transfer(tid("tr"), a, b, amount(ccy(a), 1 + rnd.nextInt(200_00)), "p2p"));
        } else if (x < 45) {
            AccountId a = client(), b = sameCcy(a);
            long exp = rnd.nextInt(4) == 0 ? 0 : now + (1 + rnd.nextInt(10)) * STEP;
            run(new Command.Reserve(tid("auth"), a, b, amount(ccy(a), 1 + rnd.nextInt(300_00)), exp, "card auth"));
        } else if (x < 58) {
            Transfer t = pick(Kind.TRANSFER);
            if (t != null) run(rnd.nextInt(3) == 0 ? new Command.Post(t.id, 1 + Math.floorMod(rnd.nextLong(), t.amount)) : new Command.Post(t.id, t.amount));
        } else if (x < 63) {
            Transfer t = pick(Kind.TRANSFER);
            if (t != null) run(new Command.Cancel(t.id));
        } else if (x < 71) {
            AccountId a = client();
            Ccy from = ccy(a), to = otherCcy(from);
            AccountId b = clientIn(to);
            long sell = amount(from, 1 + rnd.nextInt(100_00));
            long buy = convert(sell, from, to);
            String link = "fx" + (n++);
            run(new Command.Linked(link, List.of(
                new Command.Transfer(new TransferId(link + "-sell"), a, fx(from), sell, "fx sell " + from),
                new Command.Transfer(new TransferId(link + "-buy"), fx(to), b, buy, "fx buy " + to))));
        } else if (x < 81) {
            AccountId a = client();
            Outcome o = run(new Command.SubmitPayout(tid("pay"), a, nostro(ccy(a)), amount(ccy(a), 1 + rnd.nextInt(400_00)), "withdrawal"));
            if (o instanceof Outcome.Applied) engine.dispatch(now);
        } else if (x < 85) {
            Transfer t = settledPayout();
            if (t != null) run(new Command.Return(tid("ret"), t.id, 1 + Math.floorMod(rnd.nextLong(), t.postedAmount - t.returnedTotal), "returned by beneficiary bank"));
        } else {
            run(new Command.Tick());
        }
    }

    public Outcome run(Command c) {
        Outcome o = engine.apply(c, now);
        switch (o) {
            case Outcome.Applied a -> { applied.add(c); events += a.events().size(); }
            case Outcome.Rejected r -> rejected++;
            case Outcome.Replayed r -> applied.add(c);
        }
        return o;
    }

    private TransferId tid(String p) { return new TransferId(p + "_" + String.format(Locale.ROOT, "%05d", n++)); }

    private AccountId client() { return clients.get(rnd.nextInt(clients.size())); }

    private Ccy ccy(AccountId a) { return engine.state.accounts.get(a).ccy; }

    private AccountId sameCcy(AccountId a) {
        AccountId b;
        do b = clientIn(ccy(a)); while (b.equals(a));
        return b;
    }

    private AccountId clientIn(Ccy c) {
        List<AccountId> in = clients.stream().filter(x -> ccy(x) == c).toList();
        return in.get(rnd.nextInt(in.size()));
    }

    private static Ccy otherCcy(Ccy c) {
        Ccy[] all = {Ccy.GBP, Ccy.EUR, Ccy.USD, Ccy.JPY};
        return all[(c.ordinal() + 1) % 4];
    }

    /** Scaled by currency so JPY amounts look like JPY amounts. */
    private static long amount(Ccy c, long pence) {
        return switch (c) { case JPY -> pence * 2; case KWD -> pence / 2; default -> pence; };
    }

    /** Integer FX at fixed demo rates; the pools absorb the rounding. */
    private static long convert(long amount, Ccy from, Ccy to) {
        long[] perGbpMilli = {1000, 1170, 1270, 190_000}; // GBP, EUR, USD, JPY per 1 GBP, in thousandths (JPY per 100)
        long inGbpMilli = amount * 1000 / perGbpMilli[from.ordinal()];
        return Math.max(1, inGbpMilli * perGbpMilli[to.ordinal()] / 1000);
    }

    private Transfer pick(Kind kind) {
        List<Transfer> open = engine.state.transfers.values().stream()
            .filter(t -> t.kind == kind && t.status == Transfer.Status.PENDING).toList();
        return open.isEmpty() ? null : open.get(rnd.nextInt(open.size()));
    }

    private Transfer settledPayout() {
        List<Transfer> done = engine.state.transfers.values().stream()
            .filter(t -> t.kind == Kind.PAYOUT && t.payout == Payout.SETTLED && t.returnedTotal < t.postedAmount).toList();
        return done.isEmpty() ? null : done.get(rnd.nextInt(done.size()));
    }
}
