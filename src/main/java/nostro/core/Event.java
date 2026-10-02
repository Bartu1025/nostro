package nostro.core;

import nostro.core.Command.Source;
import nostro.core.Ids.AccountId;
import nostro.core.Ids.TransferId;

/** Append-only facts. State is a fold over these and nothing else. */
public sealed interface Event {

    record AccountOpened(AccountId id, Ccy ccy, boolean allowOverdraft) implements Event {}

    record TransferPending(TransferId id, AccountId debit, AccountId credit, long amount, Ccy ccy, long expiresAt,
                           String linkId, Kind kind, String ref, TransferId original, long createdAt) implements Event {}

    /** amount <= the pending amount; the difference is released. */
    record TransferPosted(TransferId id, long amount) implements Event {}

    record TransferVoided(TransferId id, VoidReason reason) implements Event {}

    record PayoutStatus(TransferId id, Payout status, Source source) implements Event {}

    enum Kind { TRANSFER, PAYOUT, RETURN }

    enum VoidReason { CANCELLED, EXPIRED, RAIL_FAILED, RECON }

    /** SUBMITTED -> DISPATCHED -> SETTLED | FAILED | UNKNOWN;  UNKNOWN -> SETTLED | FAILED (rail late answer, or reconciliation). */
    enum Payout { SUBMITTED, DISPATCHED, UNKNOWN, SETTLED, FAILED }
}
