package nostro.core;

public enum Reject {
    INVALID, UNKNOWN_ACCOUNT, CURRENCY_MISMATCH, INSUFFICIENT_FUNDS, UNKNOWN_TRANSFER,
    /** Same id, different payload. Replaying the original outcome here would be a silent correctness hole. */
    IDEMPOTENCY_CONFLICT,
    ALREADY_POSTED, ALREADY_VOIDED, EXPIRED, EXCEEDS_PENDING,
    IS_PAYOUT, NOT_PAYOUT, PAYOUT_STATE, NOT_SETTLED, EXCEEDS_ORIGINAL
}
