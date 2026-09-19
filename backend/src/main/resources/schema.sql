-- Hand-written DDL. spring.jpa.hibernate.ddl-auto=none — Hibernate never touches
-- the schema, this file is the only source of truth for it.
--
-- Every statement is IF NOT EXISTS, so spring.sql.init.mode=always is idempotent:
-- the file replays on every boot and changes nothing once the tables are there.
--
-- Tables are ordered so that every REFERENCES points at a table already created.

-- Singleton (Rule 8). CHECK (id = 1) is what makes "singleton" a fact about the
-- database rather than a comment.
--
-- "current_date" is quoted deliberately. Bare CURRENT_DATE is a SQLite literal
-- keyword: an unqualified `SELECT current_date FROM stokvel_config` returns the
-- operating system's today, not the stored value — silently, with no error. That
-- is the exact Rule 8 violation this column exists to prevent, so it is quoted
-- here and in the entity.
-- rotation_count is fixed when the stokvel is created and never changed. That is a
-- governance decision, not a technical one: if the number of rotations could be
-- revised mid-stream, the members who have already been paid out could vote to
-- extend the stokvel that the members still waiting are carrying.
CREATE TABLE IF NOT EXISTS stokvel_config (
    id                  INTEGER       PRIMARY KEY,
    contribution_amount DECIMAL(19,2) NOT NULL,
    "current_date"      DATE          NOT NULL,
    rotation_count      INTEGER       NOT NULL,
    CHECK (id = 1),
    CHECK (contribution_amount > 0),
    CHECK (rotation_count >= 1)
);

-- No position column: rotation order is created_at order (Rule 5).
-- No debt column, no is_active column: both are derived.
CREATE TABLE IF NOT EXISTS member (
    id         INTEGER   PRIMARY KEY AUTOINCREMENT,
    name       TEXT      NOT NULL,
    created_at TIMESTAMP NOT NULL
);

-- No start date: a cycle starts where the previous one's due_date left off.
-- sequence_number is UNIQUE because cycles are never reordered or deleted, which
-- is the same reason it is allowed to exist at all.
--
-- rotation_number earns its place on the same argument. Rotations are generated one
-- at a time, never in advance, so a member who joins late is appended to the tail of
-- the current rotation and is simply present in the member list when the next
-- rotation is generated. Nothing is ever inserted between existing rows and nothing
-- is renumbered — which is also why the rotation a cycle belongs to cannot be derived
-- from sequence_number alone once the member count has changed mid-stream.
CREATE TABLE IF NOT EXISTS cycle (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    sequence_number INTEGER NOT NULL UNIQUE,
    rotation_number INTEGER NOT NULL,
    due_date        DATE    NOT NULL,
    recipient_id    INTEGER NOT NULL REFERENCES member (id),
    CHECK (rotation_number >= 1)
);

-- amount_paid is GROSS, before any arrears deduction (Rule 6).
-- cycle_id is UNIQUE: a payout row is what closes a cycle, so a second one would
-- make findOpenCycle() ambiguous. The uniqueness is load-bearing, not hygiene.
CREATE TABLE IF NOT EXISTS payout (
    id           INTEGER       PRIMARY KEY AUTOINCREMENT,
    cycle_id     INTEGER       NOT NULL UNIQUE REFERENCES cycle (id),
    recipient_id INTEGER       NOT NULL REFERENCES member (id),
    amount_paid  DECIMAL(19,2) NOT NULL,
    created_at   TIMESTAMP     NOT NULL,
    CHECK (amount_paid >= 0)
);

-- No cycle_id: which cycle a payment counts toward is the allocation's job.
-- payout_id is set only on the auto-generated arrears deduction, and is UNIQUE
-- because PaymentRepository.findByPayoutId returns an Optional — a second row
-- against one payout would make that method throw rather than return.
CREATE TABLE IF NOT EXISTS payment (
    id         INTEGER       PRIMARY KEY AUTOINCREMENT,
    member_id  INTEGER       NOT NULL REFERENCES member (id),
    amount     DECIMAL(19,2) NOT NULL,
    payout_id  INTEGER       NULL UNIQUE REFERENCES payout (id),
    created_at TIMESTAMP     NOT NULL,
    CHECK (amount > 0)
);

-- Immutable. amount is never decremented; outstanding is amount minus the
-- allocations against it. No status, no amount_remaining, no is_settled.
CREATE TABLE IF NOT EXISTS debt (
    id          INTEGER       PRIMARY KEY AUTOINCREMENT,
    debtor_id   INTEGER       NOT NULL REFERENCES member (id),
    creditor_id INTEGER       NOT NULL REFERENCES member (id),
    cycle_id    INTEGER       NOT NULL REFERENCES cycle (id),
    amount      DECIMAL(19,2) NOT NULL,
    created_at  TIMESTAMP     NOT NULL,
    CHECK (amount > 0),
    CHECK (debtor_id <> creditor_id)
);

-- A slice of one payment, not a monthly total.
-- The CHECK is the belt-and-braces half of "exactly one of cycle_id / debt_id":
-- the service layer enforces it, and this makes a bug there impossible to persist.
-- (a IS NULL) <> (b IS NULL) is true only when exactly one of them is set.
CREATE TABLE IF NOT EXISTS allocation (
    id         INTEGER       PRIMARY KEY AUTOINCREMENT,
    payment_id INTEGER       NOT NULL REFERENCES payment (id),
    cycle_id   INTEGER       NULL REFERENCES cycle (id),
    debt_id    INTEGER       NULL REFERENCES debt (id),
    amount     DECIMAL(19,2) NOT NULL,
    CHECK ((cycle_id IS NULL) <> (debt_id IS NULL)),
    CHECK (amount > 0)
);

-- Build last (Rule 4). Same event-then-distribution shape as payment/allocation.
CREATE TABLE IF NOT EXISTS buyin (
    id         INTEGER       PRIMARY KEY AUTOINCREMENT,
    member_id  INTEGER       NOT NULL REFERENCES member (id),
    amount     DECIMAL(19,2) NOT NULL,
    created_at TIMESTAMP     NOT NULL,
    CHECK (amount > 0)
);

CREATE TABLE IF NOT EXISTS buyin_distribution (
    id           INTEGER       PRIMARY KEY AUTOINCREMENT,
    buyin_id     INTEGER       NOT NULL REFERENCES buyin (id),
    recipient_id INTEGER       NOT NULL REFERENCES member (id),
    amount       DECIMAL(19,2) NOT NULL,
    CHECK (amount > 0)
);
