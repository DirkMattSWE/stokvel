// The top-of-page stats, OR the create-stokvel form — never both.
//
// `config` matches StokvelConfigResponse (contributionAmount, currentDate,
// rotationCount) when a stokvel exists. When one doesn't exist yet,
// GET /api/clock returns a 409 and `config` will be `null` — that's not an
// error state to hide, it's the signal to show the create form in this same
// slot instead. No separate "setup mode" flag anywhere; the presence of
// `config` IS the mode.
function Header({ config }) {
  if (!config) {
    return (
      <header className="app-header">
        <h1>Set up your stokvel</h1>
        <form className="setup-form">
          <label>
            Contribution amount
            <input type="number" name="contributionAmount" />
          </label>
          <label>
            Start date
            <input type="date" name="startDate" />
          </label>
          <label>
            Rotation count
            <input type="number" name="rotationCount" />
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
