package nostro.core;

import nostro.core.Ids.AccountId;
import nostro.core.Ids.TransferId;

import java.util.List;

/** Everything that can change state. Ids are client-supplied and double as idempotency keys. */
public sealed interface Command {

    record OpenAccount(AccountId id, Ccy ccy, boolean allowOverdraft) implements Command {}

    /** Single-phase move: emits Pending then Posted in one atomic command. */
    record Transfer(TransferId id, AccountId debit, AccountId credit, long amount, String ref) implements Command {
        public Transfer { ref = Ids.ref(ref); }
    }

    /** Two-phase step 1: hold funds on the debit side (auth). expiresAt == 0 means no expiry. */
    record Reserve(TransferId id, AccountId debit, AccountId credit, long amount, long expiresAt, String ref) implements Command {
        public Reserve { ref = Ids.ref(ref); }
    }

    /** Two-phase step 2 (capture): post up to the held amount; the remainder is released in the same step. */
    record Post(TransferId id, long amount) implements Command {}

    /** Two-phase alternative to Post: release the whole hold. */
    record Cancel(TransferId id) implements Command {}

    /** All-or-nothing group of single-phase legs. FX is two legs in two currencies; a transfer never spans currencies. */
    record Linked(String linkId, List<Transfer> legs) implements Command {
        public Linked { Ids.safe(linkId, "linkId"); legs = List.copyOf(legs); }
    }

    /** Hold client funds toward the nostro account and queue for dispatch to the external rail. */
    record SubmitPayout(TransferId id, AccountId client, AccountId nostro, long amount, String ref) implements Command {
        public SubmitPayout { ref = Ids.ref(ref); }
    }

    /** Outbox step, logged durably BEFORE the rail is called. A crash after this leaves an UNKNOWN, never a double send. */
    record MarkDispatched(TransferId id) implements Command {}

    /** What the rail said - or what reconciliation / crash recovery decided on its behalf. */
    record RailResult(TransferId id, RailOutcome outcome, Source source) implements Command {}

    /** Funds returned by the counterparty (D+N) against a settled payout. The original is never mutated. */
    record Return(TransferId id, TransferId original, long amount, String ref) implements Command {
        public Return { ref = Ids.ref(ref); }
    }

    /** Materialises expiry of holds whose expiresAt <= now. Expiry is a predicate on now, not on ticks: a late Post fails even if no Tick ran. */
    record Tick() implements Command {}

    enum RailOutcome { OK, FAILED, TIMEOUT }

    enum Source { SYSTEM, RAIL, RECON, RECOVERY }
}
