import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // sockjs-client (used by @stomp/stompjs for the /ws fallback transport)
  // references the bare Node global `global` at module load time. The
  // browser has no such global, so without this define the import throws
  // "global is not defined" as soon as App.jsx loads it — before React ever
  // renders anything.
  define: {
    global: 'globalThis',
  },
  server: {
    proxy: {
      // Forwards anything the browser requests at /api/... to Spring Boot on
      // :8080, so the browser only ever sees requests to its own origin (:5173).
      // No cross-origin request ever happens, so there is nothing for CORS to
      // block. The backend deliberately has no @CrossOrigin config — this is
      // the dev-time fix instead, per CLAUDE.md.
      '/api': 'http://localhost:8080',
      // Same idea for the STOMP/SockJS endpoint (used from pass F6 onward).
      // ws: true also proxies the WebSocket upgrade, not just plain HTTP.
      '/ws': { target: 'http://localhost:8080', ws: true },
    },
  },
})
