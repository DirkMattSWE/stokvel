# Stokvel

A prototype of a stokvel: a rotating savings group. Every member pays a fixed
contribution each month, and each month one member, in turn, receives the pot.

**Spring Boot 4 (Java 21) · React 19 + Vite · STOMP over WebSocket · SQLite**

## The one principle

**Every fact is appended. Nothing is overwritten. Totals are derived.**

Nothing in the code does `pot += amount` or `debt -= amount`. A pot is the sum
of the allocations made to it. A debt row is never changed; what is still owed is
its amount minus what has been allocated against it. The ledger is a query over
those rows, not a table of its own. The only value in the system that ever
changes is the simulated date.

## Where to read

| File | What it answers |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | **What was decided, and why.** The full design record: the schema, the business rules, the build order, and every decision that was revised along the way. Start here. |
| [`stokvel-erd.html`](stokvel-erd.html) | The entity-relationship diagram. Open it in a browser. |

## Running it

You need Java 21 and Node 20.19 or later. Run the backend and frontend in two
terminals:

```
cd backend
./mvnw spring-boot:run          # http://localhost:8080
```

```
cd frontend
npm install
npm run dev                     # http://localhost:5173
```

Open http://localhost:5173. The database (`backend/stokvel.db`) and its tables are
created on first start. To start over, stop the backend and delete that file.

Tests: `cd backend && ./mvnw test`. That runs 89 service-layer tests against
in-memory SQLite. They are deterministic, because business logic never reads the
system clock.

## The rules, in one line each

1. **The payout fires on the due date, whatever is in the pot.** Nothing delays it.
2. **A shortfall becomes a debt**, owed by the member who didn't pay to the member
   whose pot came up short.
3. **A member who joins late goes to the back of the rotation**, and owes nothing
   for the round already in progress.
4. **A late joiner buys in when they join**, paying one contribution to each
   recipient whose round they missed. That money never goes into a pot.
5. **The rotation order is the order members joined**, and it never changes.
6. **Arrears never block a payout.** What a member owes is deducted from their own
   payout, and the ledger shows both lines.
7. **A payment settles the oldest debt first**, and only what's left goes into the
   current pot.
8. **Time is simulated.** Advancing the clock doesn't pay anyone; the payouts that
   become due fire on their own.
9. **One rotation is as long as the member count**, and the stokvel runs the number
   of rotations fixed when it was created.

A member can pay at most what they owe: their arrears plus what's left of this
cycle's contribution. The payment form is filled in with that figure and won't
go above it.

## Layout

```
backend/src/main/java/com/stokvel/
  model/        one class per table
  repository/   Spring Data JPA, thin
  service/      all business logic lives here
  controller/   thin; calls services only
  websocket/    pushes the ledger to /topic/ledger
  dto/          what crosses the wire; JPA entities never do
backend/src/main/resources/schema.sql   hand-written DDL
frontend/src/                           React view; computes no totals of its own
```

Out of scope: authentication, real money, notifications, and a background
scheduler (the clock control does that job).
