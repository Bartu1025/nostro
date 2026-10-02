package nostro;

import nostro.core.Canon;
import nostro.core.Command;
import nostro.core.Command.RailOutcome;
import nostro.core.Command.Source;
import nostro.core.Core;
import nostro.core.Event.Kind;
import nostro.core.Event.Payout;
import nostro.core.Outcome;
import nostro.core.State;
import nostro.core.Transfer;
import nostro.rail.RailPort;
import nostro.store.Store;

import java.util.ArrayList;

/** The impure shell: decide (pure) -> append (durable) -> evolve (in memory). Plus the outbox relay to the rail. */
public final class Engine {
    public final State state = new State();
    private final Store store;
    private final RailPort rail;

    /** Rebuilds state by folding the log. Verifies the hash chain while it reads. */
    public Engine(Store store, RailPort rail) {
        this.store = store;
        this.rail = rail;
        String prev = Canon.GENESIS;
        for (Store.Row r : store.read(1, store.lastSeq())) {
            if (!r.prevHash().equals(prev) || !Canon.chain(prev, r.line()).equals(r.hash()))
                throw new IllegalStateException("hash chain broken at seq " + r.seq() + "; refusing to load");
            state.evolve(Canon.decode(r.line()));
            prev = r.hash();
        }
    }

    public Outcome apply(Command c, long now) {
        Outcome o = Core.decide(state, c, now);
        if (o instanceof Outcome.Applied a && !a.events().isEmpty()) {
            store.append(a.events());
            a.events().forEach(state::evolve);
        }
        return o;
    }

    /**
     * Outbox relay. DISPATCHED is logged before the rail is called, so a crash between the two leaves a payout we
     * know we *might* have sent. That payout is never sent again; recover() marks it UNKNOWN and reconciliation decides.
     */
    public int dispatch(long now) {
        int n = 0;
        for (Transfer t : new ArrayList<>(state.transfers.values())) {
            if (t.kind != Kind.PAYOUT || t.payout != Payout.SUBMITTED) continue;
            apply(new Command.MarkDispatched(t.id), now);
            RailOutcome r = rail.send(t.id, t.amount, t.ccy, t.ref);
            apply(new Command.RailResult(t.id, r, Source.RAIL), now);
            n++;
        }
        return n;
    }

    /** After a restart: anything left DISPATCHED has no result and must not be re-sent. */
    public int recover(long now) {
        int n = 0;
        for (Transfer t : new ArrayList<>(state.transfers.values())) {
            if (t.kind != Kind.PAYOUT || t.payout != Payout.DISPATCHED) continue;
            apply(new Command.RailResult(t.id, RailOutcome.TIMEOUT, Source.RECOVERY), now);
            n++;
        }
        return n;
    }

    /** Walks the whole log and reports the first seq whose stored hash does not match its content or its predecessor. */
    public static long verifyChain(Store store) {
        String prev = Canon.GENESIS;
        for (Store.Row r : store.read(1, store.lastSeq())) {
            if (!r.prevHash().equals(prev) || !Canon.chain(prev, r.line()).equals(r.hash())) return r.seq();
            prev = r.hash();
        }
        return -1;
    }
}
