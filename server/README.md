# Noti session relay

Bun + Hono, native Bun WebSockets, and SQLite. Clients default to
`https://noti.vendua.com.br`. One relay instance connects one Noti app and one
Edge/Chrome extension per paired session. No Assaad API credentials are needed.

## Deploy with Docker Compose

Point the domain's A record at your server (and AAAA only if IPv6 works), allow
inbound TCP 80/443, then run from the repository root:

```sh
docker compose up -d --build
curl https://noti.vendua.com.br/health
```

Caddy obtains/renews HTTPS certificates and proxies WebSockets. Port 3000 is private
to the Compose network. Caddy overwrites `X-Real-IP`; `TRUST_PROXY=true` relies on
that private connection. With another proxy, terminate TLS, forward WebSocket
upgrades, and overwrite that header or disable proxy trust. Run a single relay
instance: the socket registry is local to its process.

The `noti-relay` named volume keeps SQLite, including token hashes, the latest
question batch, and recent submission results. Rebuilding/restarting preserves
pairing. Caddy certificate state uses separate volumes. Back up SQLite while the
service is stopped or through SQLite's backup API.

## Pair and study

1. In the Noti library, choose **Plataforma Assaad → Generate pairing code**.
2. Load `extensions/assaad-reader` as an unpacked extension in Edge/Chrome.
3. In Assaad's dashboard, click **Conectar ao Noti**, or open the toolbar popup.
4. Enter the six-digit code. It expires after five minutes and can be used once.
5. The paired browser streams whether Assaad is active. On a question search, click
   **Enviar questões ao Noti** to send the currently rendered page.
6. Choose **Open canvas** in Noti. Work on the native canvas, select an alternative
   in the floating sheet, then **Confirm in Assaad**.
7. The browser uses the site's actual radio/confirmation controls. Official
   correct/incorrect feedback returns to Noti. Pin questions on the board to write
   around them and include them in PDF exports.

Imported questions are not graded locally and no answer key is guessed. Both
devices must be online, and the Assaad tab must remain available for confirmation.
Interrupted confirmations are never automatically resubmitted. Check the browser
before allowing another attempt after an unknown result.

## Data and lifecycle

- Each device gets a separate 256-bit bearer and app/browser role. Tokens and codes
  are hashed in SQLite. WebSocket tokens travel in the first frame, not the URL.
  Pairing requests have attempt limits and bounded bodies.
- Pairing lasts 30 days or until either device disconnects. Revocation closes both
  sockets and deletes the session's server data. Expired sessions are cleaned up.
- The relay receives session activity (three path categories), explicitly sent
  rendered questions, selected alternative IDs, and official answer feedback.
  Cookies, Assaad credentials, browser history, hidden answer keys, and drawings
  are not collected. This is a visible integration with no stealth/logging guarantee.
- The latest batch is retained until replacement, revocation, or session expiry.
  Command records expire after 24 hours; pending commands older than two minutes
  are not replayed. The browser persists dispatch markers to avoid duplicate submits.
- Noti connects while foregrounded. Received questions/results are saved on device.
  Credentials use Android Keystore encryption and are excluded from backup/transfer.
  Ink remains in Noti's existing local save path.
- Images load anonymously over HTTPS on the tablet, with no relay token or Assaad
  cookies. Protected images may need the original browser. The extension sends image
  references and does not fetch site assets.
- There is no server analytics or request-body logging. The health endpoint contains
  no session information. Keep proxy logs free of credentials.

## API / protocol 1

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | Liveness and protocol version |
| `POST /v1/pairings/create` | App receives code, token, session ID, expiry |
| `POST /v1/pairings/join` | Extension exchanges `{code}` for a browser token |
| `GET /v1/session` | Bearer-authenticated pairing status |
| `DELETE /v1/session` | Either bearer revokes both devices |
| `GET /v1/stream` | WebSocket, first frame `{type:"authenticate",token}` |

Authenticated messages use `{type,payload}`. Browser sends `presence`, `questions`,
and `answer-result`; app sends `answer`. Both can send `ping` and receive `pong`.
Relay sends `session`, `accepted` after persisting a batch, and `error` for invalid
requests. Commands must match the session's current batch/question/alternative IDs.
Unknown fields and answer-key fields are rejected. Limit: 1 MiB, 100 questions,
12 alternatives per question. See `src/protocol.ts` and the shared fixture at
`protocol/fixtures/assaad-batch.json`.

## Development

```sh
cd server
bun install --frozen-lockfile
bun run typecheck
bun test
bun run dev
```

Tests use isolated SQLite databases and loopback WebSockets. Production clients
keep the fixed HTTPS domain. Coverage includes code expiry/reuse, role boundaries,
stale/inactive tabs, duplicate commands, reconnect, revocation, and persistence.
`bun run integration` in `extensions/assaad-reader-dev` exercises the actual DOM
extractor and answer handler through the running relay with a simulated app peer.
