package nostro;

import nostro.core.Ccy;
import nostro.core.Command;
import nostro.core.Command.RailOutcome;
import nostro.core.Command.Source;
import nostro.core.Core;
import nostro.core.Event.Payout;
import nostro.core.Ids.AccountId;
import nostro.core.Ids.TransferId;
import nostro.core.Outcome;
import nostro.core.Reject;
import nostro.core.State;
import nostro.recon.Recon;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The money paths that matter, one example each. The properties and the BFS cover the rest. */
class CoreTest {
    static final AccountId CLIENT = new AccountId("c1"), NOSTRO = new AccountId("NOSTRO:GBP"), EUR = new AccountId("c2:EUR"), FXG = new AccountId("FX:GBP"), FXE = new AccountId("FX:EUR");

    State s = new State();

    CoreTest() {
        apply(new Command.OpenAccount(NOSTRO, Ccy.GBP, true));
        apply(new Command.OpenAccount(CLIENT, Ccy.GBP, false));
        apply(new Command.OpenAccount(EUR, Ccy.EUR, false));
        apply(new Command.OpenAccount(FXG, Ccy.GBP, true));
        apply(new Command.OpenAccount(FXE, Ccy.EUR, true));
        apply(new Command.Transfer(new TransferId("dep"), NOSTRO, CLIENT, 100_00, "deposit"));
    }

    Outcome apply(Command c) { return apply(c, 0); }

    Outcome apply(Command c, long now) {
        Outcome o = Core.decide(s, c, now);
        if (o instanceof Outcome.Applied a) a.events().forEach(s::evolve);
        s.checkInvariants();
        return o;
    }

    static Reject rejected(Outcome o) { return assertInstanceOf(Outcome.Rejected.class, o).reason(); }

    @Test void same_id_same_payload_is_a_replay_but_different_payload_is_a_conflict() {
        Command.Transfer t = new Command.Transfer(new TransferId("t1"), CLIENT, NOSTRO, 10_00, "x");
        assertInstanceOf(Outcome.Applied.class, apply(t));
        assertInstanceOf(Outcome.Replayed.class, apply(t));
        assertEquals(Reject.IDEMPOTENCY_CONFLICT, rejected(apply(new Command.Transfer(new TransferId("t1"), CLIENT, NOSTRO, 11_00, "x"))));
        assertEquals(90_00, s.accounts.get(CLIENT).balance());   // charged exactly once
    }

    @Test void pending_credits_are_not_spendable() {
        apply(new Command.Reserve(new TransferId("hold"), NOSTRO, CLIENT, 50_00, 0, "incoming"));
        assertEquals(100_00, s.accounts.get(CLIENT).available());
        assertEquals(Reject.INSUFFICIENT_FUNDS, rejected(apply(new Command.Transfer(new TransferId("spend"), CLIENT, NOSTRO, 120_00, ""))));
    }

    @Test void a_hold_reduces_available_and_partial_post_releases_the_rest() {
        apply(new Command.Reserve(new TransferId("auth"), CLIENT, NOSTRO, 60_00, 0, "card auth"));
        assertEquals(40_00, s.accounts.get(CLIENT).available());
        assertInstanceOf(Outcome.Applied.class, apply(new Command.Post(new TransferId("auth"), 45_00)));
        assertEquals(55_00, s.accounts.get(CLIENT).balance());
        assertEquals(55_00, s.accounts.get(CLIENT).available());
        assertEquals(Reject.EXCEEDS_PENDING, rejected(apply(new Command.Reserve(new TransferId("a2"), CLIENT, NOSTRO, 10_00, 0, "")) instanceof Outcome.Applied
            ? apply(new Command.Post(new TransferId("a2"), 10_01)) : null));
    }

    @Test void expiry_is_a_predicate_on_now_not_on_tick() {
        apply(new Command.Reserve(new TransferId("auth"), CLIENT, NOSTRO, 60_00, 1_000, ""), 0);
        assertEquals(Reject.EXPIRED, rejected(apply(new Command.Post(new TransferId("auth"), 60_00), 1_000)));   // no Tick ran
        assertEquals(40_00, s.accounts.get(CLIENT).available());                                                 // still held until materialised
        apply(new Command.Tick(), 1_000);
        assertEquals(100_00, s.accounts.get(CLIENT).available());
    }

    @Test void fx_is_two_linked_legs_that_commit_together_or_not_at_all() {
        Command.Linked fx = new Command.Linked("fx1", List.of(
            new Command.Transfer(new TransferId("fx1-sell"), CLIENT, FXG, 150_00, ""),      // more than the client has
            new Command.Transfer(new TransferId("fx1-buy"), FXE, EUR, 175_00, "")));
        assertEquals(Reject.INSUFFICIENT_FUNDS, rejected(apply(fx)));
        assertEquals(0, s.accounts.get(EUR).balance());                                     // second leg did not sneak through
        Command.Linked ok = new Command.Linked("fx2", List.of(
            new Command.Transfer(new TransferId("fx2-sell"), CLIENT, FXG, 50_00, ""),
            new Command.Transfer(new TransferId("fx2-buy"), FXE, EUR, 58_50, "")));
        assertInstanceOf(Outcome.Applied.class, apply(ok));
        assertInstanceOf(Outcome.Replayed.class, apply(ok));
        assertEquals(58_50, s.accounts.get(EUR).balance());
        assertEquals(Reject.CURRENCY_MISMATCH, rejected(apply(new Command.Transfer(new TransferId("x"), CLIENT, EUR, 1, ""))));
    }

    @Test void a_timed_out_payout_is_unknown_never_resent_and_reconciliation_decides() {
        TransferId p = new TransferId("pay1");
        apply(new Command.SubmitPayout(p, CLIENT, NOSTRO, 30_00, "withdrawal"));
        assertEquals(Reject.PAYOUT_STATE, rejected(apply(new Command.RailResult(p, RailOutcome.OK, Source.RAIL))));   // result before dispatch is a protocol error
        apply(new Command.MarkDispatched(p));
        apply(new Command.RailResult(p, RailOutcome.TIMEOUT, Source.RAIL));
        assertEquals(Payout.UNKNOWN, s.transfers.get(p).payout);
        assertEquals(70_00, s.accounts.get(CLIENT).available());                                                 // funds stay held
        assertEquals(Reject.PAYOUT_STATE, rejected(apply(new Command.MarkDispatched(p))));                         // never re-sent
        assertEquals(Reject.IS_PAYOUT, rejected(apply(new Command.Cancel(p))));                                   // ops cannot "just void it"

        Recon.Report absent = Recon.run(s, List.of(), 10, 0);
        assertEquals(1, absent.resolutions().size());
        Recon.Report present = Recon.run(s, List.of(new Recon.Line("pay1", 30_00, Ccy.GBP)), 10, 0);
        assertEquals(new Command.RailResult(p, RailOutcome.OK, Source.RECON), present.resolutions().get(0));
        apply(present.resolutions().get(0));
        assertEquals(Payout.SETTLED, s.transfers.get(p).payout);
        assertEquals(70_00, s.accounts.get(CLIENT).balance());
        assertInstanceOf(Outcome.Replayed.class, apply(new Command.RailResult(p, RailOutcome.OK, Source.RAIL)));   // the late rail answer is a replay
        assertEquals(Reject.PAYOUT_STATE, rejected(apply(new Command.RailResult(p, RailOutcome.FAILED, Source.RAIL))));
    }

    @Test void returns_never_exceed_the_original_and_never_touch_it() {
        TransferId p = new TransferId("pay1");
        apply(new Command.SubmitPayout(p, CLIENT, NOSTRO, 30_00, ""));
        apply(new Command.MarkDispatched(p));
        apply(new Command.RailResult(p, RailOutcome.OK, Source.RAIL));
        assertEquals(Reject.EXCEEDS_ORIGINAL, rejected(apply(new Command.Return(new TransferId("r1"), p, 30_01, ""))));
        assertInstanceOf(Outcome.Applied.class, apply(new Command.Return(new TransferId("r1"), p, 20_00, "")));
        assertInstanceOf(Outcome.Applied.class, apply(new Command.Return(new TransferId("r2"), p, 10_00, "")));
        assertEquals(Reject.EXCEEDS_ORIGINAL, rejected(apply(new Command.Return(new TransferId("r3"), p, 1, ""))));
        assertEquals(100_00, s.accounts.get(CLIENT).balance());
        assertEquals(30_00, s.transfers.get(p).postedAmount);                 // original untouched
        assertEquals(Payout.SETTLED, s.transfers.get(p).payout);
    }

    @Test void recon_classifies_timing_vs_true_break() {
        TransferId a = new TransferId("payA"), b = new TransferId("payB");
        apply(new Command.SubmitPayout(a, CLIENT, NOSTRO, 10_00, ""), 100);
        apply(new Command.MarkDispatched(a), 100);
        apply(new Command.RailResult(a, RailOutcome.OK, Source.RAIL), 100);
        apply(new Command.SubmitPayout(b, CLIENT, NOSTRO, 20_00, ""), 900);
        apply(new Command.MarkDispatched(b), 900);
        apply(new Command.RailResult(b, RailOutcome.OK, Source.RAIL), 900);
        Recon.Report r = Recon.run(s, List.of(new Recon.Line("payA", 10_01, Ccy.GBP), new Recon.Line("ghost", 5, Ccy.GBP)), 1_000, 500);
        var counts = r.counts();
        assertEquals(2, counts.get(Recon.Class_.TRUE_BREAK));   // amount mismatch on A, ghost line
        assertEquals(1, counts.get(Recon.Class_.TIMING));       // B is inside the settlement window
        assertEquals(false, r.pass());
    }
}
