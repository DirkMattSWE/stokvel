// The running list of everything that's happened, oldest first — one line per
// LedgerEntry, with its slices (if any) indented underneath.
//
// `entries` matches LedgerEntry[] from the backend: at, type, member,
// counterparty, amount, cycleSequenceNumber, description, slices[].
// `type` is the enum string (PAYMENT / BUYIN / DEBT / PAYOUT / DEDUCTION) —
// used here just to label the row, nothing more yet.
function Ledger({ entries }) {
  return (
    <section className="panel panel-ledger">
      <h2>Ledger</h2>
      <ul className="ledger-list">
        {entries.map((entry, index) => (
          <li key={index} className="ledger-entry">
            <div className="ledger-line">
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
