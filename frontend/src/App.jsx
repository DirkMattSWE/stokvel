import Header from './components/Header.jsx'
import MemberPanel from './components/MemberPanel.jsx'
import CyclePanel from './components/CyclePanel.jsx'
import Ledger from './components/Ledger.jsx'

// F2: hardcoded data, matching the walkthrough's fixture (docs/walkthrough.md
// §1.1) — R500 contribution, three members, one rotation, clock at
// 2026-01-15. No network calls yet; App just owns this data and hands it
// down. In F3 these four consts get replaced by real fetches — nothing in
// the four components below has to change when that happens.

const config = {
  contributionAmount: 500,
  currentDate: '2026-01-15',
  rotationCount: 1,
}

const members = [
  { id: 1, name: 'John' },
  { id: 2, name: 'Sarah' },
  { id: 3, name: 'Thabo' },
]

const cycles = [
  {
    cycle: { id: 1, sequenceNumber: 1, recipientName: 'John', dueDate: '2026-01-31' },
    collected: 500,
    target: 1500,
    shortfall: 1000,
  },
  {
    cycle: { id: 2, sequenceNumber: 2, recipientName: 'Sarah', dueDate: '2026-02-28' },
    collected: 0,
    target: 1500,
    shortfall: 1500,
  },
  {
    cycle: { id: 3, sequenceNumber: 3, recipientName: 'Thabo', dueDate: '2026-03-31' },
    collected: 0,
    target: 1500,
    shortfall: 1500,
  },
]

const ledger = [
  {
    at: '2026-01-15T00:00:00Z',
    type: 'PAYMENT',
    member: 'John',
    counterparty: null,
    amount: 500,
    cycleSequenceNumber: 1,
    description: 'John paid R500',
    slices: [{ amount: 500, destination: 'cycle 1 pot' }],
  },
]

function App() {
  return (
    <div className="app-shell">
      <Header config={config} />
      <div className="panels">
        <MemberPanel members={members} />
        <CyclePanel cycles={cycles} />
        <Ledger entries={ledger} />
      </div>
    </div>
  )
}

export default App
