# nostro

[![ci](https://github.com/Bartu1025/nostro/actions/workflows/ci.yml/badge.svg)](https://github.com/Bartu1025/nostro/actions/workflows/ci.yml)

**A payments settlement core that knows when it doesn't know where the money is.**

`nostro` is a deterministic double-entry ledger with an outbox to an external payment rail and a reconciliation
engine. Its one hard problem is the rail call that times out: the money may or may not have moved. `nostro` never
retries that payout. It marks it `UNKNOWN`, keeps the client's funds held, and lets the bank statement decide.
Everything else here (holds, FX, returns, idempotency, replay) exists to make that path correct and provable.

Java 21, SQLite, no framework. ~1,450 lines of main code, ~500 of tests.

```
$ make demo
```

```
[1] workload   10,000 commands: 40 client accounts in 4 currencies, holds, FX, 861 payouts via a rail that times out 10% of the time
    applied 8,634  rejected 711  events 13,752  (seq 16,249)
    invariants  OK  (per-currency debits == credits, no client overdraft, open holds == pending counters)
    state_hash  3a1ea65da7ac1c65ddce388be36ad1319e31a4684772000fd7d80284624ccb61

[2] replay     fresh process, same SQLite file, fold every event, verify chain while reading
    state_hash  3a1ea65da7ac1c65ddce388be36ad1319e31a4684772000fd7d80284624ccb61   identical

[3] crash      submitted pay_crash, logged DISPATCHED, called the rail - the bank moved the money - then: simulated process crash after the rail accepted pay_crash
    recovery   restarted: 1 payout(s) were DISPATCHED with no result -> UNKNOWN. Not re-sent: we may already have paid.
    stuck view (what on-call sees):
  PAYOUT/UNKNOWN   87 open, oldest 3d
    pay_00054      USD        79.33  age 3d
    pay_00180      JPY        50026  age 3d
    pay_00187      JPY        55150  age 3d

[4] recon      bank statement: 778 lines (+1 planted amount mismatch, +1 planted unexpected debit), 2h settlement window
    RECON as-of=1760300523145466  MATCHED 733  TIMING 1  RESOLVES_UNKNOWN 86  TRUE_BREAK 2
      TRUE_BREAK       pay_00044      JPY ledger=4782 statement=4783  amount mismatch
      RESOLVES_UNKNOWN pay_00054      USD ledger=79.33 statement=-  absent past window -> FAILED, hold released
      RESOLVES_UNKNOWN pay_00340      GBP ledger=120.70 statement=120.70  on statement -> SETTLED
      ...
      TIMING           pay_06667      JPY ledger=58432 statement=-  unknown, still inside settlement window
      RESOLVES_UNKNOWN pay_crash      GBP ledger=125.00 statement=125.00  on statement -> SETTLED
      TRUE_BREAK       BANK-FEE-0001  GBP ledger=- statement=2.50  on statement, not in ledger
      status: BREAK   resolutions: 86
    applied 86 resolution(s): pay_crash -> SETTLED, 1 UNKNOWN left (inside window); invariants OK; state_hash cb0f7e3d...

[5] tamper     edited event #8212 in the SQLite file directly: PEND|tr_03141|c05:EUR|c17:EUR|62210|EUR|0||TRANSFER|p2p||...
    verify-chain  BREAK at seq 8212
    engine open   refused: hash chain broken at seq 8212; refusing to load
```

The hash in step 1 is pinned in [`GoldenHashTest`](src/test/java/nostro/GoldenHashTest.java) and checked by CI on
Linux and macOS. Same seed, same hash, any machine. That is the whole determinism claim, and it is a fact, not a
measurement.

## Deliberately out of scope

- **A matching engine, margin, or anything trading-shaped.** This is a payments ledger. The same core could carry an
  order book; it does not, and the README does not pretend it will.
- **Durability beyond SQLite's.** The log is a SQLite table in WAL mode with `synchronous=FULL` and `fullfsync` on.
  Writing a bespoke append-only log would have been more fun and less correct.
- **Consensus, replication, sharding.** One process, one file. The core is single-threaded on purpose: determinism
  comes from there.
- **A SQL read model.** The stuck-payments view and `explain` are served from the in-memory projection. A reporting
  database is a consumer of the event log, not part of the core.
- **A real rail adapter, HTTP API, auth.** The rail is an interface with one fake implementation that lies the way
  real banks do. The CLI is the API.
- **Regulatory compliance.** The reconciliation is shaped like the daily internal reconciliation an EMI runs between
  client-money records and its safeguarding account. It is the mechanism such a thing is built on, not the thing.

## Invariants, and the test that proves each

| Invariant | Where it is checked |
|---|---|
| Per currency, Σ posted debits == Σ posted credits, and the same for pending | [`State.checkInvariants`](src/main/java/nostro/core/State.java), after every command in [`LedgerProperties`](src/test/java/nostro/LedgerProperties.java) |
| A client account's `available = posted credits − posted debits − pending debits` is never negative. Pending credits are not spendable. | same; plus `pending_credits_are_not_spendable` in [`CoreTest`](src/test/java/nostro/CoreTest.java) |
| Σ open holds on an account == its pending counters (a cancelled hold can't leak a reservation) | `State.checkInvariants`, the O(n) part |
| Folding the log reproduces the live state, hash for hash, and the chain verifies | `replaying_the_log_rebuilds_the_identical_state` |
| Re-sending any already-applied command never changes state | `re_sending_any_applied_command_never_changes_state` |
| A rejected command changes nothing, not even `seq` | `a_rejected_command_leaves_state_untouched` |
| Every `Posted` was `Pending` first, for at most the held amount; nothing is voided twice | `every_posted_transfer_was_pending_first_and_posted_at_most_its_hold` |
| Terminal transfer states absorb; `Post` after expiry is rejected even if no `Tick` ran | [`TransferStateMachineTest`](src/test/java/nostro/TransferStateMachineTest.java): every command sequence to depth 6, ~1.1M paths, exhaustively |
| A payout is `SETTLED` iff posted, `FAILED` iff voided, otherwise still held; `UNKNOWN` is never re-dispatched | `State.checkInvariants`; `a_timed_out_payout_is_unknown_never_resent_and_reconciliation_decides` |
| Returns never exceed the original and never mutate it | `returns_never_exceed_the_original_and_never_touch_it` |
| The core reads no clock, no randomness, no unordered collection, no float, does no I/O | [`ArchRules`](src/test/java/nostro/ArchRules.java), at compile-class level |

Overflow is a failure, not a wrap: every arithmetic step on money goes through `Math.addExact` /
`Math.subtractExact`. A `long` that wraps would make Σdebits == Σcredits pass by accident; this way it throws.

## Determinism is enforced, not hoped for

The core (`nostro.core`) is one pure function, `Core.decide(state, command, now) -> events | reject`, and one fold,
`State.evolve(event)`. The clock is a parameter. Nothing else in the package has an ambient input. On the JVM that
takes some deliberate avoidance:

| JVM trap | Why it breaks replay | What `nostro` does |
|---|---|---|
| `HashMap` / `HashSet` iteration | Order depends on capacity and JDK version | `TreeMap` everywhere; ArchUnit bans the hash collections in `core` |
| `Map.of` / `Set.of` iteration | Order is salted **per JVM run** (`ImmutableCollections.SALT32L`) | Never iterated to produce output |
| `Object.hashCode()` | Identity hash differs per run | Only `record`s and `String`s as keys |
| `UUID.randomUUID()` inside the core | Ids generated in the core are non-replayable | Ids are client-supplied and are the idempotency keys |
| `Instant.now()`, `System.nanoTime()` | Time leaks in | `long nowMicros` parameter; ArchUnit bans the calls |
| `double` / `BigDecimal` | Rounding is a policy, not a type | `long` minor units; `Ccy` carries the scale; ArchUnit bans float fields, returns and boxed types |
| `String.format("%d")` | Digit shapes are locale-dependent | `Locale.ROOT` on anything that reaches the hash |
| Reflection-based serialization | Field order is not a contract | Hand-written canonical line format ([`Canon`](src/main/java/nostro/core/Canon.java)) |
| `Random.nextLong(bound)` | Default method; implementation may change across JDKs | Only the specified `nextInt(bound)`, `nextLong()`, `nextDouble()` |

The backstop for whatever the table misses is `GoldenHashTest` on two operating systems.

## Design

### Decide, append, evolve

```
Command ──► Core.decide(state, cmd, now)  ──► Rejected          state unchanged, id not consumed
                        │
                        └──► Applied(events) ──► store.append(events)   one SQLite transaction, hash-chained
                                                       │
                                                       └──► state.evolve(event) for each, in order
```

Replay is the same `evolve` over the same rows. There is no second code path to drift.

### Idempotency is structural

There is no idempotency-key table. Every entity-creating command carries its own id (`transfer_id`, `payout_id`),
and that id is the primary key. A repeat with the same payload is `Replayed`; a repeat with a different payload is
`Rejected(IDEMPOTENCY_CONFLICT)`, never silently answered with the first outcome. State transitions (`Post`,
`Cancel`, `RailResult`) are idempotent by terminal state: posting the same amount again is a replay, posting a
different amount is a conflict, posting a voided transfer is `ALREADY_VOIDED`. Nothing needs eviction, so nothing
can be evicted into a double-charge.

### Two-phase transfers

Each account holds four counters: `debitsPending`, `debitsPosted`, `creditsPending`, `creditsPosted`
(after [TigerBeetle](https://github.com/tigerbeetle/tigerbeetle)). `Reserve` moves an amount into pending on both
sides; `Post(n ≤ held)` moves `n` into posted and releases the rest; `Cancel` or expiry releases all of it.
Expiry is `now >= expiresAt`, evaluated on the `Post` itself. `Tick` only materialises the void event; it never
decides. Two replicas with different tick cadence therefore cannot disagree.

FX is `Linked`: two single-currency legs under one `linkId`, validated per debit account against the pre-state as a
group, applied all or none. A transfer never names a currency; it takes it from the accounts, and the accounts must
agree.

### The payout state machine

```
SubmitPayout      MarkDispatched        RailResult(OK)            ┌─────────┐
  hold client ───► SUBMITTED ───► DISPATCHED ───────────────────► │ SETTLED │ posted
  funds toward                       │                            └─────────┘
  NOSTRO                             │ RailResult(FAILED)         ┌─────────┐
                                     ├──────────────────────────► │ FAILED  │ hold released
                                     │                            └─────────┘
                                     │ RailResult(TIMEOUT)           ▲   ▲
                                     │ or crash recovery             │   │
                                     ▼                               │   │
                                  UNKNOWN ── recon: on statement ────┘   │
                                     │                                   │
                                     └───── recon: absent, past window ──┘
```

`DISPATCHED` is written to the log **before** the rail is called (transactional outbox). If the process dies
between the two, the restart finds a `DISPATCHED` payout with no result and moves it to `UNKNOWN`. `UNKNOWN` cannot
be re-dispatched (`PAYOUT_STATE`) and cannot be cancelled by an operator (`IS_PAYOUT`); only the rail's late answer
or reconciliation can move it. The client's funds stay held throughout. This is the part of the system that is not
in the tutorials.

### Reconciliation

[`Recon.run`](src/main/java/nostro/recon/Recon.java) is a pure function from (state, statement, as-of, settlement
lag) to a classified report plus the commands that would resolve it. Applying them is the caller's decision.

| Ledger says | Statement says | Class | Resolution |
|---|---|---|---|
| SETTLED | present, same amount | `MATCHED` | — |
| SETTLED | present, different amount | `TRUE_BREAK` | human |
| SETTLED | absent, inside window | `TIMING` | wait |
| SETTLED | absent, past window | `TRUE_BREAK` | human |
| UNKNOWN | present, same amount | `RESOLVES_UNKNOWN` | `RailResult(OK, RECON)` → posted |
| UNKNOWN | absent, past window | `RESOLVES_UNKNOWN` | `RailResult(FAILED, RECON)` → hold released |
| UNKNOWN | absent, inside window | `TIMING` | wait |
| FAILED | present | `TRUE_BREAK` | human (bank paid what we voided) |
| — | present | `TRUE_BREAK` | human (debit we never sent) |

Returns (`Return`) arrive D+N against a settled payout and are a new transfer the other way, capped at the original
amount across any number of partial returns. The original payout row is never touched; history is append-only.

### Storage

Events are rows: `(seq PRIMARY KEY, prev_hash, hash, line)`. `hash = SHA-256(prev_hash ‖ line)`. The engine
verifies the chain as it loads and refuses a broken file. This makes the log **tamper-evident**: an edit, deletion
or reorder is detected. It is not tamper-proof; a party who can rewrite every row can rewrite every hash. Anchoring
the head hash somewhere the writer does not control (returning it in API responses, say) is where that would go.

SQLite rather than a hand-rolled log, deliberately. Correct durability on macOS needs `F_FULLFSYNC`, not `fsync`;
directory fsync after create; recovery that truncates a torn tail but halts on mid-log corruption rather than
silently discarding committed data. SQLite has had twenty years of other people's crashes to get that right.

## Running it

```
./gradlew test            # 23 tests, ~90s (most of it is fsync; see Numbers)
make demo                 # the output above, fresh temp database
```

CLI against a database file:

```
nostro db.sqlite open NOSTRO:GBP GBP house
nostro db.sqlite open alice GBP
nostro db.sqlite transfer dep1 NOSTRO:GBP alice 10000 "deposit"
nostro db.sqlite reserve auth1 alice NOSTRO:GBP 2500 0 "card auth"
nostro db.sqlite post auth1 1999
nostro db.sqlite payout pay1 alice NOSTRO:GBP 3000 "withdrawal"
nostro db.sqlite rail pay1 TIMEOUT
nostro db.sqlite stuck
nostro db.sqlite recon statement.csv 7200000000 --apply      # ref,amount_minor,ccy
nostro db.sqlite explain alice 12                            # balance derivation up to event 12
nostro db.sqlite verify-chain
```

(`nostro` = `./gradlew -q run --args="..."`, or build a jar.)

`explain` is the evidence trail: every event that touched an account, with the running balance after each, up to
any point in the log. It is how you answer "why is this balance what it is" without trusting the balance.

## Numbers

10,000 commands take about 40 seconds on an Apple-silicon MacBook. That is ~4 ms per command, and almost all of it
is `F_FULLFSYNC`: with `synchronous=FULL` every command is one durable SQLite transaction. The core itself decides
and folds 10,000 commands in well under a second (the in-memory property runs do ~12,000 commands in ~5 s with
invariant checks after each one).

No latency percentiles are claimed. A laptop number for a single-threaded in-process ledger would be an
unfalsifiable one.

## Prior art

Account counters and two-phase transfers follow [TigerBeetle](https://github.com/tigerbeetle/tigerbeetle). The
seeded-simulation-as-proof idea is FoundationDB's and [Antithesis](https://antithesis.com)'s, scaled down to one
process. The outbox is the transactional outbox pattern. Structural idempotency through client-supplied ids is how
Stripe's API has worked for years. The reconciliation shape is the one every EMI runs every morning.

## Status

`v0.1`: complete for its stated scope. Issues and pull requests welcome; roadmap deliberately absent.

MIT.
