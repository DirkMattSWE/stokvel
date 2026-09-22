// The one control that moves simulated time. Rule 8 in the flesh: this
// button doesn't pay anyone — it moves `current_date`, and whatever payout
// that made due fires on its own, server-side. This component just shows
// where the clock stands and reads the target date the user picked.
//
// `config` matches StokvelConfigResponse — only `currentDate` is used here,
// the other fields are Header's job. `onAdvanceClock` is App.jsx's handler;
// this component holds no state and never calls the API directly.
function ClockPanel({ config, onAdvanceClock }) {
  function handleSubmit(event) {
    event.preventDefault()
    const form = event.target
    const targetDate = form.targetDate.value
    if (!targetDate) return
    onAdvanceClock(targetDate)
    form.reset()
  }

  return (
    <section className="panel panel-clock">
      <h2>Clock</h2>
      <p className="clock-current">
        Today is <strong>{config?.currentDate ?? '—'}</strong>
      </p>
      <form className="advance-form" onSubmit={handleSubmit}>
        <label>
          Move time to
          <input type="date" name="targetDate" required disabled={!config} />
        </label>
        <button type="submit" disabled={!config}>
          Advance clock
        </button>
      </form>
    </section>
  )
}

export default ClockPanel
