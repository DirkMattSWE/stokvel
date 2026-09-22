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
