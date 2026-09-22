// A floating form over the main screen. Purely presentational — it holds no
// state of its own; the parent decides when it's open and what's inside it.
function Modal({ onClose, children }) {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(event) => event.stopPropagation()}>
        {children}
      </div>
    </div>
  )
}

export default Modal
