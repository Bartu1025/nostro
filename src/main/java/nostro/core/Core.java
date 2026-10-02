package nostro.core;

import nostro.core.Command.RailOutcome;
import nostro.core.Command.Source;
import nostro.core.Event.Kind;
import nostro.core.Event.Payout;
import nostro.core.Event.VoidReason;
import nostro.core.Ids.AccountId;
import nostro.core.Ids.TransferId;
import nostro.core.Outcome.Applied;
import nostro.core.Transfer.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

import static nostro.core.Outcome.REPLAYED;
import static nostro.core.Outcome.applied;
import static nostro.core.Outcome.reject;
import static nostro.core.Reject.*;

/**
 * The only place business rules live. Pure: reads state and now, returns events or a reject, mutates nothing.
 * No clock, no randomness, no I/O, no threads - see ArchRules for how that is enforced rather than hoped for.
 */
public final class Core {
    private Core() {}

    public static Outcome decide(State s, Command c, long now) {
        return switch (c) {
            case Command.OpenAccount x -> openAccount(s, x);
            case Command.Transfer x -> transfer(s, x, null, now);
            case Command.Reserve x -> reserve(s, x, now);
            case Command.Post x -> post(s, x, now);
            case Command.Cancel x -> cancel(s, x);
            case Command.Linked x -> linked(s, x, now);
            case Command.SubmitPayout x -> submitPayout(s, x, now);
            case Command.MarkDispatched x -> markDispatched(s, x);
            case Command.RailResult x -> railResult(s, x);
            case Command.Return x -> ret(s, x, now);
            case Command.Tick x -> tick(s, now);
        };
    }

    private static Outcome openAccount(State s, Command.OpenAccount c) {
        Account a = s.accounts.get(c.id());
        if (a != null)
            return a.ccy == c.ccy() && a.allowOverdraft == c.allowOverdraft() ? REPLAYED
                : reject(IDEMPOTENCY_CONFLICT, "account " + c.id() + " exists with different attributes");
        return applied(new Event.AccountOpened(c.id(), c.ccy(), c.allowOverdraft()));
    }

    private static Outcome transfer(State s, Command.Transfer c, String linkId, long now) {
        Transfer t = s.transfers.get(c.id());
        if (t != null) return t.matches(c.debit(), c.credit(), c.amount(), Kind.TRANSFER, 0, linkId, c.ref(), null) ? REPLAYED : conflict(c.id());
        Outcome bad = validateLeg(s, c.debit(), c.credit(), c.amount(), true);
        if (bad != null) return bad;
        return applied(pending(s, c.id(), c.debit(), c.credit(), c.amount(), 0, linkId, Kind.TRANSFER, c.ref(), null, now),
                       new Event.TransferPosted(c.id(), c.amount()));
    }

    private static Outcome reserve(State s, Command.Reserve c, long now) {
        Transfer t = s.transfers.get(c.id());
        if (t != null) return t.matches(c.debit(), c.credit(), c.amount(), Kind.TRANSFER, c.expiresAt(), null, c.ref(), null) ? REPLAYED : conflict(c.id());
        if (c.expiresAt() != 0 && c.expiresAt() <= now) return reject(INVALID, "expiresAt is in the past");
        Outcome bad = validateLeg(s, c.debit(), c.credit(), c.amount(), true);
        if (bad != null) return bad;
        return applied(pending(s, c.id(), c.debit(), c.credit(), c.amount(), c.expiresAt(), null, Kind.TRANSFER, c.ref(), null, now));
    }

    private static Outcome post(State s, Command.Post c, long now) {
        Transfer t = s.transfers.get(c.id());
        if (t == null) return reject(UNKNOWN_TRANSFER, c.id().v());
        if (t.kind == Kind.PAYOUT) return reject(IS_PAYOUT, "payouts settle via RailResult, not Post");
        return switch (t.status) {
            case POSTED -> c.amount() == t.postedAmount ? REPLAYED : conflict(c.id());
            case VOIDED -> reject(ALREADY_VOIDED, c.id() + " was voided: " + t.voidReason);
            case PENDING -> {
                if (t.expiresAt != 0 && now >= t.expiresAt) yield reject(EXPIRED, c.id() + " expired at " + t.expiresAt);
                if (c.amount() <= 0) yield reject(INVALID, "amount must be positive");
                if (c.amount() > t.amount) yield reject(EXCEEDS_PENDING, c.amount() + " > held " + t.amount);
                yield applied(new Event.TransferPosted(c.id(), c.amount()));
            }
        };
    }

    private static Outcome cancel(State s, Command.Cancel c) {
        Transfer t = s.transfers.get(c.id());
        if (t == null) return reject(UNKNOWN_TRANSFER, c.id().v());
        if (t.kind == Kind.PAYOUT) return reject(IS_PAYOUT, "payouts fail via RailResult, not Cancel");
        return switch (t.status) {
            case VOIDED -> REPLAYED;
            case POSTED -> reject(ALREADY_POSTED, c.id().v());
            case PENDING -> applied(new Event.TransferVoided(c.id(), VoidReason.CANCELLED));
        };
    }

    private static Outcome linked(State s, Command.Linked c, long now) {
        if (c.legs().isEmpty()) return reject(INVALID, "linked group has no legs");
        int existing = 0;
        for (Command.Transfer leg : c.legs()) {
            Transfer t = s.transfers.get(leg.id());
            if (t == null) continue;
            if (!t.matches(leg.debit(), leg.credit(), leg.amount(), Kind.TRANSFER, 0, c.linkId(), leg.ref(), null)) return conflict(leg.id());
            existing++;
        }
        if (existing == c.legs().size()) return REPLAYED;
        if (existing > 0) return reject(IDEMPOTENCY_CONFLICT, "group " + c.linkId() + " was applied with a different set of legs");

        TreeMap<AccountId, Long> need = new TreeMap<>();
        TreeSet<TransferId> ids = new TreeSet<>();
        for (Command.Transfer leg : c.legs()) {
            if (!ids.add(leg.id())) return reject(INVALID, "duplicate leg id " + leg.id());
            Outcome bad = validateLeg(s, leg.debit(), leg.credit(), leg.amount(), false);
            if (bad != null) return bad;
            need.merge(leg.debit(), leg.amount(), Math::addExact);
        }
        // Funds are checked per debit account over the whole group, against the pre-state: all legs or none.
        for (var e : need.entrySet()) {
            Account a = s.accounts.get(e.getKey());
            if (!a.allowOverdraft && a.available() < e.getValue())
                return reject(INSUFFICIENT_FUNDS, a.id + " available " + a.available() + " < " + e.getValue());
        }
        List<Event> out = new ArrayList<>();
        for (Command.Transfer leg : c.legs()) {
            out.add(pending(s, leg.id(), leg.debit(), leg.credit(), leg.amount(), 0, c.linkId(), Kind.TRANSFER, leg.ref(), null, now));
            out.add(new Event.TransferPosted(leg.id(), leg.amount()));
        }
        return new Applied(out);
    }

    private static Outcome submitPayout(State s, Command.SubmitPayout c, long now) {
        Transfer t = s.transfers.get(c.id());
        if (t != null) return t.matches(c.client(), c.nostro(), c.amount(), Kind.PAYOUT, 0, null, c.ref(), null) ? REPLAYED : conflict(c.id());
        Outcome bad = validateLeg(s, c.client(), c.nostro(), c.amount(), true);
        if (bad != null) return bad;
        return applied(pending(s, c.id(), c.client(), c.nostro(), c.amount(), 0, null, Kind.PAYOUT, c.ref(), null, now),
                       new Event.PayoutStatus(c.id(), Payout.SUBMITTED, Source.SYSTEM));
    }

    private static Outcome markDispatched(State s, Command.MarkDispatched c) {
        Transfer t = s.transfers.get(c.id());
        if (t == null) return reject(UNKNOWN_TRANSFER, c.id().v());
        if (t.kind != Kind.PAYOUT) return reject(NOT_PAYOUT, c.id().v());
        return switch (t.payout) {
            case SUBMITTED -> applied(new Event.PayoutStatus(c.id(), Payout.DISPATCHED, Source.SYSTEM));
            case DISPATCHED -> REPLAYED;
            // UNKNOWN is deliberately not re-dispatchable: we may already have paid. Only recon or a late rail answer moves it.
            default -> reject(PAYOUT_STATE, c.id() + " is " + t.payout + ", will not re-send");
        };
    }

    private static Outcome railResult(State s, Command.RailResult c) {
        Transfer t = s.transfers.get(c.id());
        if (t == null) return reject(UNKNOWN_TRANSFER, c.id().v());
        if (t.kind != Kind.PAYOUT) return reject(NOT_PAYOUT, c.id().v());
        return switch (t.payout) {
            case SUBMITTED -> reject(PAYOUT_STATE, "result for " + c.id() + " before it was dispatched");
            case SETTLED -> c.outcome() == RailOutcome.OK ? REPLAYED : reject(PAYOUT_STATE, c.id() + " already SETTLED");
            case FAILED -> c.outcome() == RailOutcome.FAILED ? REPLAYED : reject(PAYOUT_STATE, c.id() + " already FAILED");
            case DISPATCHED, UNKNOWN -> switch (c.outcome()) {
                case OK -> applied(new Event.TransferPosted(c.id(), t.amount), new Event.PayoutStatus(c.id(), Payout.SETTLED, c.source()));
                case FAILED -> applied(new Event.TransferVoided(c.id(), c.source() == Source.RECON ? VoidReason.RECON : VoidReason.RAIL_FAILED),
                                       new Event.PayoutStatus(c.id(), Payout.FAILED, c.source()));
                case TIMEOUT -> t.payout == Payout.UNKNOWN ? REPLAYED : applied(new Event.PayoutStatus(c.id(), Payout.UNKNOWN, c.source()));
            };
        };
    }

    private static Outcome ret(State s, Command.Return c, long now) {
        Transfer t = s.transfers.get(c.id());
        Transfer o = s.transfers.get(c.original());
        if (t != null) return o != null && t.matches(o.credit, o.debit, c.amount(), Kind.RETURN, 0, null, c.ref(), c.original()) ? REPLAYED : conflict(c.id());
        if (o == null) return reject(UNKNOWN_TRANSFER, c.original().v());
        if (o.kind != Kind.PAYOUT) return reject(NOT_PAYOUT, c.original() + " is not a payout");
        if (o.payout != Payout.SETTLED) return reject(NOT_SETTLED, c.original() + " is " + o.payout);
        if (c.amount() <= 0) return reject(INVALID, "amount must be positive");
        if (Math.addExact(o.returnedTotal, c.amount()) > o.postedAmount) return reject(EXCEEDS_ORIGINAL, "returns would exceed " + o.postedAmount);
        // Money flows back the way it went: nostro -> client. The original payout is untouched; history is append-only.
        return applied(pending(s, c.id(), o.credit, o.debit, c.amount(), 0, null, Kind.RETURN, c.ref(), c.original(), now),
                       new Event.TransferPosted(c.id(), c.amount()));
    }

    private static Outcome tick(State s, long now) {
        List<Event> out = new ArrayList<>();
        // ponytail: O(n) scan per tick; index open holds by expiresAt if the pending set gets large
        for (Transfer t : s.transfers.values())
            if (t.status == Status.PENDING && t.kind != Kind.PAYOUT && t.expiresAt != 0 && t.expiresAt <= now)
                out.add(new Event.TransferVoided(t.id, VoidReason.EXPIRED));
        return new Applied(out);
    }

    /** Null when the leg is valid. Currency is taken from the accounts: a transfer never names one. */
    private static Outcome validateLeg(State s, AccountId debit, AccountId credit, long amount, boolean checkFunds) {
        if (amount <= 0) return reject(INVALID, "amount must be positive");
        if (debit.equals(credit)) return reject(INVALID, "debit and credit are the same account");
        Account d = s.accounts.get(debit), c = s.accounts.get(credit);
        if (d == null) return reject(UNKNOWN_ACCOUNT, debit.v());
        if (c == null) return reject(UNKNOWN_ACCOUNT, credit.v());
        if (d.ccy != c.ccy) return reject(CURRENCY_MISMATCH, d.ccy + " -> " + c.ccy + " (use Linked for FX)");
        if (checkFunds && !d.allowOverdraft && d.available() < amount)
            return reject(INSUFFICIENT_FUNDS, debit + " available " + d.available() + " < " + amount);
        return null;
    }

    private static Event.TransferPending pending(State s, TransferId id, AccountId debit, AccountId credit, long amount, long expiresAt,
                                                 String linkId, Kind kind, String ref, TransferId original, long now) {
        return new Event.TransferPending(id, debit, credit, amount, s.accounts.get(debit).ccy, expiresAt, linkId, kind, ref, original, now);
    }

    private static Outcome conflict(TransferId id) {
        return reject(IDEMPOTENCY_CONFLICT, id + " exists with a different payload");
    }
}
