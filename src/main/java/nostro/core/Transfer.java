package nostro.core;

import nostro.core.Event.Kind;
import nostro.core.Event.Payout;
import nostro.core.Event.VoidReason;
import nostro.core.Ids.AccountId;
import nostro.core.Ids.TransferId;

import java.util.Objects;

public final class Transfer {
    public enum Status { PENDING, POSTED, VOIDED }

    public final TransferId id;
    public final AccountId debit, credit;
    /** The held amount. postedAmount may be less; the difference was released. */
    public final long amount;
    public final Ccy ccy;
    public final long expiresAt;
    public final String linkId;
    public final Kind kind;
    public final String ref;
    public final TransferId original;
    public final long createdAt;

    public Status status = Status.PENDING;
    public long postedAmount;
    public VoidReason voidReason;
    public Payout payout;
    public long returnedTotal;

    Transfer(Event.TransferPending p) {
        id = p.id(); debit = p.debit(); credit = p.credit(); amount = p.amount(); ccy = p.ccy();
        expiresAt = p.expiresAt(); linkId = p.linkId(); kind = p.kind(); ref = p.ref(); original = p.original(); createdAt = p.createdAt();
    }

    /** Structural idempotency: a repeated command is a replay only if every field it carries matches. */
    boolean matches(AccountId debit, AccountId credit, long amount, Kind kind, long expiresAt, String linkId, String ref, TransferId original) {
        return this.debit.equals(debit) && this.credit.equals(credit) && this.amount == amount && this.kind == kind
            && this.expiresAt == expiresAt && Objects.equals(this.linkId, linkId) && this.ref.equals(ref) && Objects.equals(this.original, original);
    }
}
