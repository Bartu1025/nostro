package nostro;

import nostro.core.Ccy;
import nostro.core.Command;
import nostro.core.Core;
import nostro.core.Event;
import nostro.core.Ids.AccountId;
import nostro.core.Ids.TransferId;
import nostro.core.Outcome;
import nostro.core.Reject;
import nostro.core.State;
import nostro.core.Transfer;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive enumeration of every command sequence (depth 6, ~1.1M paths) against one two-phase transfer, instead of a
 * TLA+ spec: the state space is tiny, so a plain depth-first walk gives the same guarantee and runs in CI in seconds.
 */
class TransferStateMachineTest {
    static final AccountId A = new AccountId("a"), B = new AccountId("b");
    static final TransferId T = new TransferId("t");
    static final long EXP = 1_000;

    static final List<Command> ALPHABET = List.of(
        new Command.Reserve(T, A, B, 100, EXP, ""),
        new Command.Post(T, 100),
        new Command.Post(T, 40),
        new Command.Cancel(T),
        new Command.Tick());

    record Node(State s, long now, List<Command> path) {}

    @Test
    void every_sequence_to_depth_6_keeps_invariants_and_terminal_states_absorb() {
        ArrayDeque<Node> stack = new ArrayDeque<>();
        stack.push(new Node(fresh(), 0, List.of()));
        TreeSet<String> hashes = new TreeSet<>();
        int explored = 0;

        while (!stack.isEmpty()) {
            Node n = stack.pop();
            explored++;
            hashes.add(n.s.hash());
            if (n.path.size() == 6) continue;
            for (Command c : ALPHABET) {
                for (long dt : new long[]{1, EXP}) {                 // before and at/after expiry
                    State s = copy(n.s);
                    long now = n.now + dt;
                    Transfer.Status before = s.transfers.get(T) == null ? null : s.transfers.get(T).status;
                    Outcome o = Core.decide(s, c, now);
                    if (o instanceof Outcome.Applied a) a.events().forEach(s::evolve);
                    s.checkInvariants();
                    check(before, s.transfers.get(T), c, o, now, n.path);
                    List<Command> path = new java.util.ArrayList<>(n.path); path.add(c);
                    stack.push(new Node(s, now, path));
                }
            }
        }
        assertTrue(explored > 100_000, "explored " + explored);
        assertTrue(hashes.size() < 100, "distinct states " + hashes.size()); // the machine is small; if this grows, something leaks
    }

    /** The transition rules, stated once, checked on every edge. */
    static void check(Transfer.Status before, Transfer after, Command c, Outcome o, long now, List<Command> path) {
        String at = path + " then " + c + " @" + now + " -> " + o;
        if (before != null && before != Transfer.Status.PENDING) {
            // terminal states absorb: nothing but a replay of the same terminal command is accepted
            assertEquals(before, after.status, at);
            assertTrue(!(o instanceof Outcome.Applied a) || a.events().isEmpty(), at);   // an idle Tick is Applied([]) and that is fine
        }
        switch (c) {
            case Command.Post p -> {
                if (before == Transfer.Status.PENDING && now >= EXP)
                    assertTrue(o instanceof Outcome.Rejected r && r.reason() == Reject.EXPIRED, at); // predicate on now, independent of Tick
                if (o instanceof Outcome.Applied) assertEquals(p.amount(), after.postedAmount, at);
            }
            case Command.Tick t -> {
                if (before == Transfer.Status.PENDING && now >= EXP)
                    assertEquals(Transfer.Status.VOIDED, after.status, at);
            }
            case Command.Cancel x -> {
                if (before == Transfer.Status.VOIDED) assertTrue(o instanceof Outcome.Replayed, at);
                if (before == Transfer.Status.POSTED) assertTrue(o instanceof Outcome.Rejected, at);
            }
            case Command.Reserve r -> {
                if (before != null) assertTrue(o instanceof Outcome.Replayed, at);   // same payload -> replay, whatever the status
            }
            default -> { }
        }
    }

    static State fresh() {
        State s = new State();
        s.evolve(new Event.AccountOpened(A, Ccy.GBP, false));
        s.evolve(new Event.AccountOpened(B, Ccy.GBP, true));
        s.evolve(new Event.TransferPending(new TransferId("fund"), B, A, 1_000, Ccy.GBP, 0, null, Event.Kind.TRANSFER, "", null, 0));
        s.evolve(new Event.TransferPosted(new TransferId("fund"), 1_000));
        return s;
    }

    /** States are small here; rebuild by replaying the dump is overkill, so copy field by field through events. */
    static State copy(State s) {
        State c = fresh();
        Transfer t = s.transfers.get(T);
        if (t == null) return c;
        c.evolve(new Event.TransferPending(t.id, t.debit, t.credit, t.amount, t.ccy, t.expiresAt, t.linkId, t.kind, t.ref, t.original, t.createdAt));
        if (t.status == Transfer.Status.POSTED) c.evolve(new Event.TransferPosted(t.id, t.postedAmount));
        if (t.status == Transfer.Status.VOIDED) c.evolve(new Event.TransferVoided(t.id, t.voidReason));
        c.seq = s.seq;
        return c;
    }
}
