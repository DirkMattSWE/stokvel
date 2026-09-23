import { useRef, useState } from 'react'
import Modal from './Modal.jsx'

// Displays the members, in rotation order, plus two overlay forms: adding a
// member, and recording a payment for one.
//
// `members` matches the shape of MemberResponse[] from the backend
// (id, name, createdAt). `onAddMember`/`onRecordPayment`/`onFetchMaxPayable` are handlers
// App.jsx owns — this component only reads form values and calls them; it
// never touches the API or shared state itself. `showAddMember` and
// `payingMember` are pure UI state (which overlay is open), not business
// state, so they live here rather than in App.jsx.
//
// The payment form is capped at MaxPayableResponse.maxPayable, fetched when
// the form opens and never computed here. The amount is prefilled with it so
// a member types *down* from what they owe rather than guessing upward into
// a refusal. The cap is the input's `max`, so the browser refuses above it
// before the request is sent; the backend refuses above it anyway.
function MemberPanel({ members, onAddMember, onRecordPayment, onFetchMaxPayable }) {
  const [showAddMember, setShowAddMember] = useState(false)
  const [payingMember, setPayingMember] = useState(null)
  const [maxPayable, setMaxPayable] = useState(null)
  // Which member's form is open right now, readable after an await —
  // payingMember in this closure would be stale by then.
  const openFor = useRef(null)

  async function openPayment(member) {
    openFor.current = member.id
    setPayingMember(member)
    setMaxPayable(null)
    const max = await onFetchMaxPayable(member.id)
    // Closed or switched to another member while the request was in flight —
    // don't show this member's cap on someone else's form.
    if (openFor.current !== member.id) return
    if (!max) {
      closePayment()
      return
    }
    setMaxPayable(max)
  }

  function closePayment() {
    openFor.current = null
    setPayingMember(null)
    setMaxPayable(null)
  }

  function handleAddMemberSubmit(event) {
    event.preventDefault()
    const form = event.target
    const name = form.name.value.trim()
    if (!name) return
    onAddMember(name)
    setShowAddMember(false)
  }

  function handlePaymentSubmit(event) {
    event.preventDefault()
    const form = event.target
    const amount = Number(form.amount.value)
    if (!amount) return
    onRecordPayment(payingMember.id, amount)
    closePayment()
  }

  return (
    <section className="panel">
      <h2>Members</h2>
      <ol className="member-list">
        {members.map((member, index) => (
          <li key={member.id} className="member-row">
            <span className="member-position">{index + 1}</span>
            <span className="member-name">{member.name}</span>
            <button type="button" className="member-pay-button" onClick={() => openPayment(member)}>
              Pay
            </button>
          </li>
        ))}
      </ol>

      <button type="button" onClick={() => setShowAddMember(true)}>
        + Add member
      </button>

      {showAddMember && (
        <Modal onClose={() => setShowAddMember(false)}>
          <h3>Add member</h3>
          <form className="setup-form" onSubmit={handleAddMemberSubmit}>
            <label>
              Name
              <input type="text" name="name" placeholder="Name" required autoFocus />
            </label>
            <button type="submit">Add member</button>
          </form>
        </Modal>
      )}

      {payingMember && (
        <Modal onClose={closePayment}>
          <h3>Record payment — {payingMember.name}</h3>
          {!maxPayable ? (
            <p className="payment-note">Working out what {payingMember.name} owes…</p>
          ) : Number(maxPayable.maxPayable) === 0 ? (
            <p className="payment-note">{payingMember.name} owes nothing right now — there is nothing to pay.</p>
          ) : (
            <form className="setup-form" onSubmit={handlePaymentSubmit}>
              <p className="payment-note">
                Arrears R{maxPayable.arrears} + this cycle R{maxPayable.contributionDue} = at most R
                {maxPayable.maxPayable}
              </p>
              <label>
                Amount
                <input
                  type="number"
                  name="amount"
                  min="0.01"
                  max={maxPayable.maxPayable}
                  step="0.01"
                  defaultValue={maxPayable.maxPayable}
                  required
                  autoFocus
                />
              </label>
              <button type="submit">Record payment</button>
            </form>
          )}
        </Modal>
      )}
    </section>
  )
}

export default MemberPanel
