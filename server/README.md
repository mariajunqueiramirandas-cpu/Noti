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

The `noti-relay` named volume keeps SQLite, including token hashes, saved question-set archives, the latest
import, and recent submission results. Rebuilding/restarting preserves
pairing. Caddy certificate state uses separate volumes. Back up SQLite while the
service is stopped or through SQLite's backup API.

## Single-tablet mode

Noti also offers **Plataforma Assaad → This tablet**. Sign into the real site in
its Android WebView, choose **Save questions**, then **Canvas** to minimize it.
The same saved library and approved queue run locally, without this relay, an
extension, or another device. Ordinary browser CORS is enforced. A read-only
metadata request checks the authenticated connection before queued submissions;
there is no native HTTP or relay fallback if CORS fails. Live Assaad WebView
login/CORS behavior has not been verified on a physical tablet.

Website cookies remain in private WebView storage and are excluded from backup
and device transfer. Native code never reads them. Assaad API calls use the user's
network connection and the same fixed pacing as the extension.

## Pair and study

1. In the Noti library, choose **Plataforma Assaad → Generate pairing code**.
2. Load `extensions/assaad-reader` as an unpacked extension in Edge/Chrome.
3. In Assaad's dashboard, click **Conectar ao Noti**, or open the toolbar popup.
4. Enter the six-digit code. It expires after five minutes and can be used once.
5. The paired browser streams whether Assaad is active. On a question search, click
   **Enviar questões ao Noti** to send the currently rendered page.
6. Choose **Open canvas** in Noti. Work on the native canvas, select an alternative
   in the floating sheet, then **Confirm in Assaad**.
7. The browser verifies the original account and question, then confirms by UUID
   through the question-bank API. Official grading returns to Noti. Pin questions on the board to write
   around them and include them in PDF exports.

Imported questions are not graded locally and no answer key is guessed. Multiple sets and draft choices persist on device. Approve one answer or use
**Saved sets → Submit saved answers**; the queue waits for Edge and runs while Noti
is foregrounded. It supports pause/resume/cancel. A valid Assaad login in Edge is
required, but the exact question page need not stay open for API-backed imports.
The extension reuses a tab or opens one background tab for an approved command.
Old DOM-only imports retain their original-tab requirement.
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
  A SHA-256 account reference scopes saved sets.
  Cookies, Assaad credentials, browser history, hidden answer keys, and drawings
  are not collected. This is a visible integration with no stealth/logging guarantee.
- Up to 100 immutable sets are archived per paired session, until forgotten,
  revocation, or expiry. The device keeps up to 100 sets without time expiry.
  An app can restore saved API-backed sets into a renewed pairing. Forgetting a set
  frees archive capacity, unless it has a pending command.
  Command records expire after 24 hours; pending commands older than two minutes
  are not replayed. The browser persists dispatch markers to avoid duplicate submits.
- Noti connects while foregrounded. Received questions/results are saved on device.
  Credentials use Android Keystore encryption and are excluded from backup/transfer.
  Ink remains in Noti's existing local save path.
- Images load anonymously over HTTPS on the tablet, with no relay token or Assaad
  cookies. Protected images may need the original browser. The extension sends image
  references. The tablet copies images locally when possible (4 MiB each, 64 MiB
  total) and shows the offline image count. Protected images may still need connectivity.
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
and `answer-result`; app sends `answer`, `restore-questions`, and `forget-questions`. Both can send `ping` and receive `pong`.
Relay sends `session`, `accepted`, `restored`, `forgotten`, and `error`.
Commands must match an archived set/question/alternative. Forwarded browser commands
include the immutable set for API validation. Results may be `confirmed`, `blocked`,
`unknown`, or legacy `failed`. API sets do not require a foreground question tab.
Unknown fields and answer-key fields are rejected. Sets are limited to 1,000,000
JSON characters within a 1 MiB frame, 100 questions,
12 alternatives per question. See `src/protocol.ts` and the shared fixture at
`protocol/fixtures/assaad-batch.json`.

## Browser-only platform requests and fair use

All Assaad API calls execute inside the user’s own Edge page and network connection.
This relay never proxies a platform request or receives Assaad cookies/access tokens.
Only pairing and bounded study messages cross this server. The extension performs
fixed operations, with five seconds between operations, two seconds between requests,
and at most six submitted answers per minute. Rate limits enforce Retry-After (at
least 60 seconds), persisted browser-locally. There is no pagination crawl or automatic
write retry. The user’s VPN/proxy, if any, determines that connection’s public IP.

See [the endpoint investigation](../docs/integrations/plataforma-assaad-api.md) and
its machine-readable catalogue for 62 source-confirmed contracts and unverified live
behavior. Development tests have not mutated the real Assaad account.

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
