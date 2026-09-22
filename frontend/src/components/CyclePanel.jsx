// One row per cycle, each with a progress bar for pot collected vs target.
//
// `cycles` matches CycleStateResponse[] from the backend — each entry has a
// nested `cycle` (CycleResponse: sequenceNumber, recipientName, dueDate, ...)
// plus `collected`, `target`, `shortfall`, all derived server-side. Nothing
// here computes a percentage from raw payments — that would be re-deriving a
// number the backend already worked out, which is the client-side version of
// `pot += amount`.
function CyclePanel({ cycles }) {
  return (
    <section className="panel">
      <h2>Cycles</h2>
      <div className="cycle-list">
        {cycles.map((state) => {
          const percent = Math.min(100, (state.collected / state.target) * 100)
          return (
            <div className="cycle-row" key={state.cycle.id}>
              <div className="cycle-label">
                <span className="recipient">
                  #{state.cycle.sequenceNumber} · {state.cycle.recipientName}
                </span>
                <span className="amounts">
                  R{state.collected} of R{state.target}
                </span>
              </div>
              <div className="progress-track">
                <div className="progress-fill" style={{ width: `${percent}%` }} />
              </div>
            </div>
          )
        })}
      </div>
    </section>
  )
}

export default CyclePanel
