import { useState } from 'react'
import Modal from './Modal.jsx'

// Displays the members, in rotation order, plus two overlay forms: adding a
// member, and recording a payment for one.
//
// `members` matches the shape of MemberResponse[] from the backend
// (id, name, createdAt). `onAddMember`/`onRecordPayment` are handlers
// App.jsx owns — this component only reads form values and calls them; it
// never touches the API or shared state itself. `showAddMember` and
// `payingMember` are pure UI state (which overlay is open), not business
// state, so they live here rather than in App.jsx.
function MemberPanel({ members, onAddMember, onRecordPayment }) {
  const [showAddMember, setShowAddMember] = useState(false)
  const [payingMember, setPayingMember] = useState(null)

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
    setPayingMember(null)
  }

  return (
    <section className="panel">
      <h2>Members</h2>
      <ol className="member-list">
        {members.map((member, index) => (
          <li key={member.id} className="member-row">
            <span className="member-position">{index + 1}</span>
            <span className="member-name">{member.name}</span>
            <button type="button" className="member-pay-button" onClick={() => setPayingMember(member)}>
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
        <Modal onClose={() => setPayingMember(null)}>
          <h3>Record payment — {payingMember.name}</h3>
          <form className="setup-form" onSubmit={handlePaymentSubmit}>
            <label>
              Amount
              <input type="number" name="amount" min="0.01" step="0.01" required autoFocus />
            </label>
            <button type="submit">Record payment</button>
          </form>
        </Modal>
      )}
    </section>
  )
}

export default MemberPanel
