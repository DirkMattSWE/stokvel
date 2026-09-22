// The running list of everything that's happened, oldest first — one line per
// LedgerEntry, with its slices (if any) indented underneath.
//
// `entries` matches LedgerEntry[] from the backend: at, type, member,
// counterparty, amount, cycleSequenceNumber, description, slices[].
// `type` is the enum string (PAYMENT / BUYIN / DEBT / PAYOUT / DEDUCTION) —
// used here just to label the row, nothing more yet.
// timeZone: 'UTC' is not optional. Every created_at is stamped from
// simulatedNow() — the simulated business date at midnight UTC — so formatting
// in the browser's local zone would render 2026-10-01T00:00:00Z as 30 Sep in any
// UTC-negative offset. Built once at module scope, not per render.
const LEDGER_DATE = new Intl.DateTimeFormat('en-ZA', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  timeZone: 'UTC',
})

function Ledger({ entries }) {
  return (
    <section className="panel panel-ledger">
      <h2>Ledger</h2>
      <ul className="ledger-list">
        {entries.map((entry, index) => (
          <li key={index} className="ledger-entry">
            <div className="ledger-line">
              <span className="ledger-date">{LEDGER_DATE.format(new Date(entry.at))}</span>
              <span className={`ledger-type ${entry.type}`}>{entry.type}</span>
              <span>
                {entry.member}
                {entry.counterparty && ` → ${entry.counterparty}`}
              </span>
              <span className="ledger-amount">R{entry.amount}</span>
            </div>

            {entry.slices.length > 0 && (
              <ul className="ledger-slices">
                {entry.slices.map((slice, sliceIndex) => (
                  <li key={sliceIndex}>
                    R{slice.amount} → {slice.destination}
                  </li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

export default Ledger
