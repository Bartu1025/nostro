package nostro;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import nostro.core.Command;
import nostro.core.Outcome;
import nostro.rail.FakeRail;
import nostro.store.SqliteStore;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each property runs the real simulator for a random seed and length, then checks something that must hold for
 * every reachable state. Invariants are checked after every single command, not only at the end.
 */
class LedgerProperties {

    @Property(tries = 60)
    void ledger_is_always_balanced_and_never_overdrawn(@ForAll @LongRange(min = 1, max = 1_000_000) long seed,
                                                       @ForAll @IntRange(min = 1, max = 400) int ops) throws Exception {
        Run r = Run.fresh(seed, false);
        for (int i = 0; i < ops; i++) {
            r.sim.step();
            r.engine.state.checkInvariants();   // Σdebits == Σcredits per currency, no overdraft, holds == pending counters
        }
        r.close();
    }

    @Property(tries = 40)
    void replaying_the_log_rebuilds_the_identical_state(@ForAll @LongRange(min = 1, max = 1_000_000) long seed,
                                                        @ForAll @IntRange(min = 1, max = 400) int ops) throws Exception {
        Run r = Run.fresh(seed, true);
        r.sim.steps(ops);
        String live = r.engine.state.hash();
        long seq = r.engine.state.seq;
        r.close();
        try (SqliteStore store = new SqliteStore(r.db.toString())) {
            Engine replayed = new Engine(store, r.rail);
            assertEquals(seq, replayed.state.seq);
            assertEquals(live, replayed.state.hash());
            assertEquals(-1, Engine.verifyChain(store));
        }
    }

    @Property(tries = 40)
    void re_sending_any_applied_command_never_changes_state(@ForAll @LongRange(min = 1, max = 1_000_000) long seed,
                                                            @ForAll @IntRange(min = 1, max = 300) int ops) throws Exception {
        Run r = Run.fresh(seed, false);
        r.sim.steps(ops);
        String before = r.engine.state.hash();
        for (Command c : r.sim.applied) {
            if (c instanceof Command.Tick) continue;                  // idempotent by construction, but time has moved on
            Outcome o = r.engine.apply(c, r.sim.now);
            assertTrue(o instanceof Outcome.Replayed || o instanceof Outcome.Rejected,
                "re-sent " + c + " was applied again: " + o);        // a stale RailResult on a terminal payout rejects; nothing re-applies
            assertEquals(before, r.engine.state.hash(), "state changed after re-sending " + c);
        }
        r.close();
    }

    @Property(tries = 40)
    void a_rejected_command_leaves_state_untouched(@ForAll @LongRange(min = 1, max = 1_000_000) long seed,
                                                   @ForAll @IntRange(min = 1, max = 300) int ops) throws Exception {
        Run r = Run.fresh(seed, false);
        for (int i = 0; i < ops; i++) {
            String before = r.engine.state.hash();
            long seqBefore = r.engine.state.seq;
            r.sim.step();
            if (r.sim.rejected > r.rejectedSeen) {
                r.rejectedSeen = r.sim.rejected;
                assertEquals(before, r.engine.state.hash());
                assertEquals(seqBefore, r.engine.state.seq);
            }
        }
        r.close();
    }

    @Property(tries = 40)
    void every_posted_transfer_was_pending_first_and_posted_at_most_its_hold(@ForAll @LongRange(min = 1, max = 1_000_000) long seed,
                                                                             @ForAll @IntRange(min = 1, max = 300) int ops) throws Exception {
        Run r = Run.fresh(seed, false);
        r.sim.steps(ops);
        var seen = new java.util.TreeMap<String, Long>();
        {
            SqliteStore store = r.store;
            for (var row : store.read(1, store.lastSeq())) {
                var e = nostro.core.Canon.decode(row.line());
                switch (e) {
                    case nostro.core.Event.TransferPending p -> assertTrue(seen.put(p.id().v(), p.amount()) == null, "pending twice " + p.id());
                    case nostro.core.Event.TransferPosted p -> { Long held = seen.get(p.id().v()); assertTrue(held != null && p.amount() <= held, "posted without hold " + p.id()); }
                    case nostro.core.Event.TransferVoided v -> assertTrue(seen.containsKey(v.id().v()), "voided without hold " + v.id());
                    default -> { }
                }
            }
        }
        r.close();
    }

    /** One simulator on a temp SQLite file. */
    static final class Run {
        final Path db; final SqliteStore store; final FakeRail rail; final Engine engine; final Simulation sim;
        int rejectedSeen;

        private Run(Path db, SqliteStore store, FakeRail rail, Engine engine, Simulation sim) {
            this.db = db; this.store = store; this.rail = rail; this.engine = engine; this.sim = sim;
        }

        /** file=true gives the real durable store (fsync per command, slow); file=false is in-memory SQLite, same code path minus the disk. */
        static Run fresh(long seed, boolean file) throws Exception {
            Path db = Files.createTempFile("nostro-prop", ".db");
            Files.delete(db);
            db.toFile().deleteOnExit();
            SqliteStore store = new SqliteStore(file ? db.toString() : ":memory:");
            FakeRail rail = new FakeRail(seed + 1, 0.05, 0.10);
            Engine engine = new Engine(store, rail);
            Simulation sim = new Simulation(seed, engine, rail);
            sim.setup();
            return new Run(db, store, rail, engine, sim);
        }

        void close() { store.close(); }
    }
}
