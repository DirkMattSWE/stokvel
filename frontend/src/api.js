// One wrapper around fetch, used for every call to the backend.
//
// Its only job is turning a bad response into a JS Error whose message is
// something a person wrote. ApiExceptionHandler on the backend maps every
// business-rule refusal to a ProblemDetail body — {"detail": "...", ...} —
// and `detail` is the human-readable sentence (e.g. "No stokvel exists yet.
// Create it before anything else."). Throwing that string as the Error
// message means every caller can just `catch (err) { show(err.message) }`
// and get the real refusal text, not a stack trace.

const BASE = '/api'

export async function apiFetch(path, options = {}) {
  const response = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })

  if (!response.ok) {
    // .json() can itself fail if the body isn't JSON (or is empty) — caught
    // so a broken error response doesn't crash the error handler.
    const body = await response.json().catch(() => null)
    throw new Error(body?.detail ?? `Request failed: ${response.status}`)
  }

  // Success with no body (not currently returned by any endpoint here, but
  // cheap to handle) — .json() on an empty body would throw.
  if (response.status === 204) return null

  return response.json()
}

// One named function per backend endpoint, each a thin call to apiFetch.
// Field names match the DTOs' record components exactly — Jackson
// serializes/deserializes Java records by accessor name, so contributionAmount,
// startDate, memberId etc. below are not a convention, they're the wire format.

export function fetchClock() {
  return apiFetch('/clock')
}

export function advanceClock(targetDate) {
  return apiFetch('/clock/advance', {
    method: 'POST',
    body: JSON.stringify({ targetDate }),
  })
}

export function createStokvel(contributionAmount, startDate, rotationCount) {
  return apiFetch('/setup/stokvel', {
    method: 'POST',
    body: JSON.stringify({ contributionAmount, startDate, rotationCount }),
  })
}

export function fetchMembers() {
  return apiFetch('/setup/members')
}

export function addMember(name) {
  return apiFetch('/setup/members', {
    method: 'POST',
    body: JSON.stringify({ name }),
  })
}

export function recordPayment(memberId, amount) {
  return apiFetch('/payments', {
    method: 'POST',
    body: JSON.stringify({ memberId, amount }),
  })
}

// The most a member can pay right now — arrears plus what is left of this
// cycle's contribution. Derived server-side by the same method recordPayment
// refuses against, so the form's cap and the refusal are one number.
export function fetchMaxPayable(memberId) {
  return apiFetch(`/payments/max/${memberId}`)
}

export function fetchCycles() {
  return apiFetch('/cycles')
}

export function fetchLedger() {
  return apiFetch('/ledger')
}
