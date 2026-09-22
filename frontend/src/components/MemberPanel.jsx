// Displays the members, in rotation order. Read-only — this component never
// changes anything, it just draws what it's handed.
//
// `members` matches the shape of MemberResponse[] from the backend
// (id, name, createdAt) — kept the same shape here even with hardcoded data,
// so nothing about this file has to change once F3 wires it to the real fetch.
function MemberPanel({ members }) {
  return (
    <section className="panel">
      <h2>Members</h2>
      <ol className="member-list">
        {members.map((member, index) => (
          <li key={member.id} className="member-row">
            <span className="member-position">{index + 1}</span>
            <span>{member.name}</span>
          </li>
        ))}
      </ol>
    </section>
  )
}

export default MemberPanel
