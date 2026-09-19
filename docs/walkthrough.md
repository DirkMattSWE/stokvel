# Walkthrough — how the code actually runs

CLAUDE.md records *what was decided and why*. This file records *what happens when
the code runs*: which rows get written, in which order, by which class. It grows one
section per service as the build goes.

Sections: **1. Recording a payment** (commit `d3e6169`). Payout, clock, ledger and
buy-in sections come as those services are built.

---

## 1. Recording a payment

### 1.0 What was actually touched in `d3e6169`

In the order the work happened:

| # | File | New or changed | What |
|---|---|---|---|
| 1 | `service/ArrearsService.java` | **new** (read half only) | `outstandingFor`, `totalOutstandingFor` — both derived, writes nothing |
| 2 | `repository/DebtRepository.java` | changed | ordering method gained an `id` tie-break |
| 3 | `service/PaymentService.java` | changed (v1 → v2) | Rule 7 `settleDebts`, `recordArrearsDeduction`, relaxed closed-rotation guard |
| 4 | `repository/AllocationRepository.java` | changed | `findByPaymentId` now also `LEFT JOIN FETCH`es `cycle`, orders by `id` |
| 5 | `dto/RecordPaymentRequest.java`, `dto/PaymentResponse.java`, `dto/AllocationResponse.java` | **new** | what crosses the wire |
| 6 | `controller/PaymentController.java` | **new** (was an empty stub) | `POST /api/payments` |
| 7 | `controller/ApiExceptionHandler.java` | **new** | 400 / 409 mapping |
| 8 | `model/StokvelConfig.java`, `repository/StokvelConfigRepository.java` | changed | `simulatedNow()` and `require()`, shared by every service |
| 9 | `service/StokvelSetupService.java` | changed | uses those two instead of its own private copies |

**`PaymentRepository` was not touched.** The two repositories that changed were
`AllocationRepository` (the fetch join) and `DebtRepository` (the ordering
tie-break).

### 1.1 The fixture used in every example below

Contribution R500, three members, one rotation, clock starts 2026-01-15.

| Cycle | Due date | Recipient |
|---|---|---|
| 1 | 2026-01-31 | John |
| 2 | 2026-02-28 | Sarah |
| 3 | 2026-03-31 | Thabo |

"The open cycle" means **the earliest cycle with no payout row** — not a date
comparison. A payout row existing is what closes a cycle.

### 1.2 The simple path: no debt

`POST /api/payments` with `{"memberId": 2, "amount": 500.00}` (Sarah).

```
PaymentController.recordPayment
  └─ PaymentService.recordPayment(2, 500.00)          @Transactional starts
       ├─ configRepository.require()                  → config, current_date = 2026-01-15
       ├─ requireMember(2)                            → Sarah, or 400
       ├─ requireMoney(500.00)                        → > 0, ≤ 2 decimals, or 400
       ├─ cycleRepository.findOpenCycle()             → cycle 1
       ├─ arrearsService.outstandingFor(Sarah)        → [] (no debts)
       ├─ INSERT payment  (member=Sarah, amount=500.00, payout_id=NULL,
       │                   created_at=2026-01-15T00:00Z ← the SIMULATED clock)
       ├─ settleDebts(...) over [] → returns 500.00 untouched
       ├─ INSERT allocation (payment=that one, cycle_id=1, debt_id=NULL, 500.00)
       └─ findByPaymentId → the allocations, with cycle/debt/creditor pre-loaded
  └─ PaymentResponse.from(...)                        @Transactional committed
```

Two rows written. The pot for cycle 1 is now `SUM(allocation.amount WHERE
cycle_id = 1)` = R500. **Nothing incremented a stored total** — there is no stored
total to increment.

### 1.3 `settleDebts` — the part that is a loop, not recursion

It calls nothing that calls it back. It walks a list once:

```java
BigDecimal left = amount;
for (OutstandingDebt debt : owed) {        // oldest first — order decided by ArrearsService
    if (left.signum() == 0) break;         // nothing left to spread
    BigDecimal slice = left.min(debt.outstanding());   // the SMALLER of the two
    allocate(payment, null, debt.debt(), slice);       // one allocation row
    left = left.subtract(slice);
}
return left;                               // survivors go to the pot
```

`left.min(outstanding)` is the whole rule. Three cases, all from the same two lines:

**Case A — pays more than owed.** John owes Sarah R200. Open cycle is 2. He pays R500.

| Iteration | `left` before | debt outstanding | `slice` | row written | `left` after |
|---|---|---|---|---|---|
| 1 | 500.00 | 200.00 | **200.00** | allocation → debt | 300.00 |
| — | loop ends (no more debts) | | | allocation → cycle 2 pot, 300.00 | 0 |

One payment row, two allocation rows. Ledger reads: *R500 received — R200 settled
what he owed Sarah, R300 reached the February pot.*

**Case B — pays less than owed.** John owes Sarah R900. He pays R500.

| Iteration | `left` before | debt outstanding | `slice` | row written | `left` after |
|---|---|---|---|---|---|
| 1 | 500.00 | 900.00 | **500.00** | allocation → debt | 0 |

One payment, one allocation, nothing to the pot. The debt row still says R900 — it is
**never decremented**. Next time anyone asks, outstanding is `900 − 500 = 400`.
Partial settlement needed no code.

**Case C — several debts, oldest first.** John owes Sarah R200 (written first) and
Thabo R300 (written second). He pays R250.

| Iteration | `left` before | debt | outstanding | `slice` | `left` after |
|---|---|---|---|---|---|
| 1 | 250.00 | to Sarah | 200.00 | 200.00 | 50.00 |
| 2 | 50.00 | to Thabo | 300.00 | 50.00 | 0 |

Sarah's debt is fully settled, Thabo's is R250 short, nothing reaches the pot.

Both debts were written by the same payout, so they share a `created_at` **to the
day** — the simulated clock has no time-of-day. That is why
`findAllByDebtorOrderByCreatedAtAscIdAsc` tie-breaks on `id`: without it, "oldest
first" would be whatever order SQLite felt like returning, and the same payment could
settle different debts on different runs.

### 1.4 Why one payment row, not several

A payment is the fact **money arrived** — one event, one amount, the figure actually
handed over. Splitting R500 into a R200 payment and a R300 payment records two events
that never happened. The allocations are the *interpretation*; the payment is the
*fact*.

The schema enforces a version of this: `payment.payout_id` is `UNIQUE`, so one payout
can only ever carry one deduction payment, and `findByPayoutId` returns an `Optional`
that a second row would make throw.

### 1.5 Why `recordArrearsDeduction` is a separate method

It cannot call `recordPayment`, because it differs in two things that are not
parameters:

| | `recordPayment` | `recordArrearsDeduction` |
|---|---|---|
| `payout_id` on the payment row | `NULL` — voluntary | set — system-generated |
| leftover after debts | goes to the open cycle's pot | **must not exist**; it throws |
| who calls it | `PaymentController` | `PayoutService` (Rule 6), not yet built |

They share the part that genuinely is the same: `settleDebts`. So Rule 7 has one
implementation, and the two callers differ only where they actually differ. The
alternative — a boolean on `recordPayment` that changes what the method does — is the
thing this avoids.

**What it will look like when `PayoutService` exists.** John's turn, pot R1000, he
owes R200:

```
payout row      cycle 3, recipient John, amount_paid = 1000.00   ← GROSS, always
payment row     member John, amount 200.00, payout_id = <that payout>
allocation      → debt (to Sarah), 200.00
```

The ledger shows both lines, and the net R800 is `payout.amount_paid − payment.amount`
— derived at read time, stored nowhere. Rule 6's `max(0, pot − debt)` floor lives in
`PayoutService`, which decides the R200; `recordArrearsDeduction` refuses if that
number is bigger than what is actually owed.

### 1.6 The closed-rotation guard, and why it is conditional

```java
if (openCycle.isEmpty() && owed.isEmpty()) → 409
```

Both halves matter. No open cycle means every cycle has paid out. But a member
settling arrears after the last payout is making a perfectly legitimate payment —
there is simply nowhere to *pot* anything left over. So:

- owes something, pays ≤ what they owe, rotation closed → **accepted**, all of it to debts
- owes nothing, rotation closed → **409**
- owes R200, pays R500, rotation closed → **409**, and the message says why: *"That is more than the member owes, and every cycle has already paid out."*

### 1.7 The two repository changes

**`AllocationRepository.findByPaymentId`** gained `LEFT JOIN FETCH a.cycle` and
`ORDER BY a.id`:

```sql
SELECT a FROM Allocation a
  LEFT JOIN FETCH a.cycle
  LEFT JOIN FETCH a.debt d
  LEFT JOIN FETCH d.creditor
 WHERE a.payment.id = :paymentId
 ORDER BY a.id ASC
```

- **Why fetch `cycle` now:** `open-in-view=false`, so the persistence context is
  closed by the time `PaymentResponse.from(...)` runs in the controller. Touching an
  un-fetched `allocation.cycle` there throws `LazyInitializationException`. Before
  this commit nothing read `cycle` off an allocation, so nothing noticed.
- **Why `LEFT` on every hop:** an allocation has a cycle **or** a debt, never both. An
  inner join on either side silently drops the other half of the rows — code that
  looks like it works and loses half the ledger.
- **Why `ORDER BY a.id`:** allocations are written debts-first, pot-last, so `id`
  order *is* the order the rule applied them. The response reads in that order.

**`DebtRepository`** — `findAllByDebtorOrderByCreatedAtAsc` became
`findAllByDebtorOrderByCreatedAtAscIdAsc`, for the same-day tie explained in §1.3.

### 1.8 The transport layer, field by field

**In:** `RecordPaymentRequest(Long memberId, BigDecimal amount)`. Deliberately no
cycle (the server decides where it lands) and no date (it happens on the simulated
clock's today — Rule 8).

**Out:** `PaymentResponse`

| Field | Where it comes from | Why it is on the wire |
|---|---|---|
| `id`, `amount`, `createdAt` | the payment row | `createdAt` is the *simulated* date |
| `memberId`, `memberName` | flattened from the lazy `Member` | so the client never holds an entity |
| `deductedFromPayoutId` | `payment.payout_id` | non-null only for a Rule 6 deduction — how the ledger tells "John paid" from "R200 was taken out of John's payout" |
| `allocations` | one per allocation row | the split *is* the rule being visible |

**`AllocationResponse`** — `destination` is `POT` or `DEBT`, then
`cycleSequenceNumber` **or** `debtId` + `creditorName`, exactly mirroring which column
the row has. `destination` is spelled out rather than left for the client to infer
from which fields are null: the server owns what a row means, and a client working it
out from nulls is a second implementation of the rule waiting to disagree.

**`PaymentController`** is four lines of body: unpack, call, map, `201 Created`. It
decides nothing. There is no `PUT` and no `DELETE` — not because they were forgotten,
but because there is no `updatePayment` or `deletePayment` to call.

**`ApiExceptionHandler`** (`@RestControllerAdvice`) turns service refusals into HTTP:

| Exception | Status | Meaning |
|---|---|---|
| `IllegalArgumentException` | **400** | the request is wrong — bad amount, unknown member |
| `IllegalStateException` | **409** | the request is fine, the stokvel's state refuses it |

Unmapped, both arrive as a 500 with a stack trace in the body. The service messages
are written to be read by a person, so the message becomes the response body.

### 1.9 What does *not* exist yet

- **Nothing writes `debt` rows.** That is Rule 2, inside `PayoutService`. Until it is
  built, the Rule 7 path above only runs against debts a test inserts directly.
- **Nothing calls `recordArrearsDeduction`.** Its caller is `PayoutService` (Rule 6).
- **No broadcast.** `LedgerBroadcaster` is still an empty stub; the STOMP push on
  every ledger write comes with `LedgerService`.
- **Tests:** 9 cover v1 allocation, validation and the clock stamp. **Rule 7
  allocation tests and a `@WebMvcTest` for the controller are outstanding** — deferred
  deliberately, also recorded in CLAUDE.md.

### 1.10 Try it by hand

```bash
cd backend && ./mvnw spring-boot:run     # SQLite file stokvel.db, created on boot

curl -X POST localhost:8080/api/setup/stokvel \
  -H 'Content-Type: application/json' \
  -d '{"contributionAmount":500.00,"startDate":"2026-01-15","rotationCount":1}'

curl -X POST localhost:8080/api/setup/members \
  -H 'Content-Type: application/json' -d '{"name":"John"}'

curl -X POST localhost:8080/api/payments \
  -H 'Content-Type: application/json' -d '{"memberId":1,"amount":500.00}'
```

The last call returns the payment with its allocations. `sqlite3 backend/stokvel.db
"SELECT * FROM allocation"` shows the same rows from underneath.
