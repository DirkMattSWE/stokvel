import { useEffect, useState } from 'react'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import Header from './components/Header.jsx'
import ClockPanel from './components/ClockPanel.jsx'
import MemberPanel from './components/MemberPanel.jsx'
import CyclePanel from './components/CyclePanel.jsx'
import Ledger from './components/Ledger.jsx'
import {
  fetchClock,
  fetchMembers,
  fetchCycles,
  fetchLedger,
  createStokvel,
  addMember,
  recordPayment,
  fetchMaxPayable,
  advanceClock,
} from './api.js'

// F3: real fetches replace F2's hardcoded consts. Built polling-first per
// CLAUDE.md — refresh() is called after every write, not pushed to by a
// socket yet (that's F6). One refresh() function is the only thing that
// re-reads server state, so there is exactly one place that can drift from it.

function App() {
  const [config, setConfig] = useState(null)
  const [members, setMembers] = useState([])
  const [cycles, setCycles] = useState([])
  const [ledger, setLedger] = useState([])
  const [error, setError] = useState(null)

  // Re-reads clock, members and cycles together — the three things that can
  // change on every write. GET /api/clock 409s when no stokvel exists yet;
  // that's not a failure to surface, it's the signal Header uses to show the
  // create-stokvel form instead of the stats bar.
  async function refresh() {
    try {
      const freshConfig = await fetchClock()
      setConfig(freshConfig)
      const [freshMembers, freshCycles] = await Promise.all([fetchMembers(), fetchCycles()])
      setMembers(freshMembers)
      setCycles(freshCycles)
    } catch (err) {
      if (err.message.includes('No stokvel')) {
        setConfig(null)
        setMembers([])
        setCycles([])
      } else {
        setError(err.message)
      }
    }
  }

  // Ledger is GET-once (initial load) per CLAUDE.md; every write below that
  // produces ledger rows re-fetches it explicitly since there's no socket yet.
  async function refreshLedger() {
    try {
      setLedger(await fetchLedger())
    } catch {
      // No stokvel yet — nothing to show. refresh()'s error path already
      // covers surfacing a real failure, so this stays silent.
    }
  }

  useEffect(() => {
    refresh()
    refreshLedger()
  }, [])

  // STOMP over SockJS, per CLAUDE.md item 7: the socket only ever triggers a
  // re-read, it never carries state the client trusts on its own. On every
  // /topic/ledger push we both take the pushed ledger directly (it's already
  // the full payload LedgerService produces) and call refresh(), because
  // nothing broadcasts GET /api/cycles and a payment/payout changes that too.
  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      reconnectDelay: 5000,
      onConnect: () => {
        setError(null)
        client.subscribe('/topic/ledger', (message) => {
          setLedger(JSON.parse(message.body))
          refresh()
        })
      },
      onWebSocketError: () => {
        setError('Lost connection to the server — retrying…')
      },
      onStompError: (frame) => {
        setError(frame.headers?.message ?? 'WebSocket protocol error')
      },
      onDisconnect: () => {
        setError('Disconnected from the server — retrying…')
      },
    })

    client.activate()
    return () => client.deactivate()
  }, [])

  async function handleCreateStokvel(contributionAmount, startDate, rotationCount) {
    try {
      setError(null)
      await createStokvel(contributionAmount, startDate, rotationCount)
      await refresh()
    } catch (err) {
      setError(err.message)
    }
  }

  async function handleAddMember(name) {
    try {
      setError(null)
      const response = await addMember(name)
      await refresh()
      // addMember only broadcasts (and only needs a ledger re-read) when it
      // wrote a buy-in — a founding member wrote no ledger rows.
      if (response.buyin) await refreshLedger()
    } catch (err) {
      setError(err.message)
    }
  }

  async function handleRecordPayment(memberId, amount) {
    try {
      setError(null)
      await recordPayment(memberId, amount)
      await refresh()
      await refreshLedger()
    } catch (err) {
      setError(err.message)
    }
  }

  // A read, not a write — no refresh. Returns null on failure so the caller
  // can close its form; the refusal text still lands in the error banner.
  async function handleFetchMaxPayable(memberId) {
    try {
      setError(null)
      return await fetchMaxPayable(memberId)
    } catch (err) {
      setError(err.message)
      return null
    }
  }

  async function handleAdvanceClock(targetDate) {
    try {
      setError(null)
      await advanceClock(targetDate)
      await refresh()
      await refreshLedger()
    } catch (err) {
      setError(err.message)
    }
  }

  return (
    <div className="app-shell">
      {error && (
        <div className="error-banner" role="alert">
          {error}
        </div>
      )}
      <Header config={config} onCreateStokvel={handleCreateStokvel} />
      <div className="panels">
        <ClockPanel config={config} onAdvanceClock={handleAdvanceClock} />
        <MemberPanel
          members={members}
          onAddMember={handleAddMember}
          onRecordPayment={handleRecordPayment}
          onFetchMaxPayable={handleFetchMaxPayable}
        />
        <CyclePanel cycles={cycles} />
        <Ledger entries={ledger} />
      </div>
    </div>
  )
}

export default App
