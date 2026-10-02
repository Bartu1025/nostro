package nostro.core;

import nostro.core.Ids.AccountId;

/** Four counters, TigerBeetle style. Liability-side convention: a client's balance is what we owe them. */
public final class Account {
    public final AccountId id;
    public final Ccy ccy;
    /** True for house accounts that mirror the outside world (nostro, FX pools). False for client money. */
    public final boolean allowOverdraft;
    public long debitsPending, debitsPosted, creditsPending, creditsPosted;

    Account(AccountId id, Ccy ccy, boolean allowOverdraft) {
        this.id = id;
        this.ccy = ccy;
        this.allowOverdraft = allowOverdraft;
    }

    public long balance() { return Math.subtractExact(creditsPosted, debitsPosted); }

    /** Spendable now. Pending credits are deliberately NOT included: counting them is the classic overdraft bug. */
    public long available() { return Math.subtractExact(balance(), debitsPending); }
}
