// The top-of-page stats, OR the create-stokvel form — never both.
//
// `config` matches StokvelConfigResponse (contributionAmount, currentDate,
// rotationCount) when a stokvel exists. When one doesn't exist yet,
// GET /api/clock returns a 409 and `config` will be `null` — that's not an
// error state to hide, it's the signal to show the create form in this same
// slot instead. No separate "setup mode" flag anywhere; the presence of
// `config` IS the mode.
function Header({ config, onCreateStokvel }) {
  if (!config) {
    function handleSubmit(event) {
      event.preventDefault()
      const form = event.target
      const contributionAmount = Number(form.contributionAmount.value)
      const startDate = form.startDate.value
      const rotationCount = Number(form.rotationCount.value)
      onCreateStokvel(contributionAmount, startDate, rotationCount)
    }

    return (
      <header className="app-header">
        <h1>Set up your stokvel</h1>
        <form className="setup-form" onSubmit={handleSubmit}>
          <label>
            Contribution amount
            <input type="number" name="contributionAmount" min="0.01" step="0.01" required />
          </label>
          <label>
            Start date
            <input type="date" name="startDate" required />
          </label>
          <label>
            Rotation count
            <input type="number" name="rotationCount" defaultValue={1} min="1" required />
          </label>
          <button type="submit">Create</button>
        </form>
      </header>
    )
  }

  return (
    <header className="app-header">
      <h1>Stokvel</h1>
      <div className="header-stats">
        <span className="stat-pill">
          <strong>R{config.contributionAmount}</strong> per member
        </span>
        <span className="stat-pill">
          <strong>{config.currentDate}</strong>
        </span>
        <span className="stat-pill">
          <strong>{config.rotationCount}</strong> rotation(s)
        </span>
      </div>
    </header>
  )
}

export default Header
