# Noti session relay

Bun + Hono, native Bun WebSockets, and SQLite. Clients default to
`https://noti.vendua.com.br`. One relay instance connects one Noti app and one
Edge/Chrome extension per paired session. No Assaad API credentials are needed.

## Deploy with Dokploy

Use **Docker Compose**, the GitHub repository, branch **main**, and Compose Path
**`./server/compose.yml`**. Leave the Environment section empty: **no `.env`, API
keys, JWT secret, database password, or Assaad credentials are required**. Pairing
tokens are generated securely at runtime and stored as hashes in SQLite.

1. Save the GitHub source settings. **On Push** is supported. Optional watch paths:
   `server/**`; leave them empty to redeploy on every main-branch update.
2. In **Domains → Add Domain**, set host **`noti.vendua.com.br`**, service **`relay`**,
   container port **`3000`**, path **`/`**, and enable **HTTPS / Let's Encrypt**.
   The upstream container uses **HTTP**; Dokploy terminates public TLS.
3. Point the domain's DNS A record at the Dokploy server (AAAA only if IPv6 works).
4. Enable **Isolated Deployments** if available. Dokploy adds the service network
   and connects its Traefik proxy automatically when the domain is configured.
5. Use **Preview Compose** to confirm the relay has the generated domain labels
   and network, then **Deploy**. Domain changes require another deployment.
6. Open `https://noti.vendua.com.br/health`; expect
   `{"ok":true,"service":"noti-assaad-relay","protocol":1}`.

The relay exposes port 3000 only inside Docker; no host port is published. Dokploy
handles HTTPS certificates and WebSocket upgrades. There is no second Caddy or
Traefik container in the production Compose file, no hardcoded container/network
name, and no repository bind mount to break across deployments. Use **one relay
instance**: the live WebSocket registry is process-local. Docker Stack/Swarm
replicas are not supported by this SQLite deployment.

The non-root container has a read-only root filesystem, bounded temporary storage,
a 256 MiB memory limit, rotated logs, and a health check. The `noti-relay` named
volume persists `/data/noti.sqlite` and its WAL files across container replacement.
Keep the Dokploy application/project name and volume to preserve pairings and
saved sets. Configure volume backups; use SQLite's backup API or stop the relay
before making a filesystem backup. Never delete the volume during redeployment.

`NODE_ENV=production`, `PORT=3000`, `DATABASE_PATH=/data/noti.sqlite`, and
`TRUST_PROXY=true` are already set in Compose. Proxy trust uses Traefik's sanitized
`X-Real-IP` for pairing rate limits; keep the relay private and Traefik's
`forwardedHeaders.insecure` disabled. If another trusted CDN/proxy sits in front
of Dokploy, configure its trusted IP ranges in Dokploy/Traefik rather than trusting
arbitrary client-supplied headers. No Assaad request is sent through this relay.

Official references: [Dokploy Compose domains](https://docs.dokploy.com/docs/core/docker-compose/domains),
[isolated deployments](https://docs.dokploy.com/docs/core/docker-compose/utilities),
and [environment handling](https://docs.dokploy.com/docs/core/docker-compose).

## Standalone hosting without Dokploy

The root **`compose.yaml`** is only for a dedicated server where Caddy can bind
ports 80/443. Do not select that file in Dokploy. For standalone hosting, point DNS
at the server, allow ports 80/443, then run from the repository root:

```sh
docker compose -f compose.yaml up -d --build
curl https://noti.vendua.com.br/health
```

Caddy obtains/renews HTTPS certificates, forwards WebSockets, and overwrites
`X-Real-IP`. Its certificate state uses separate volumes. The SQLite retention
and single-instance requirements above apply to both deployment modes.

## Single-tablet mode

Noti also offers **Plataforma Assaad → This tablet**. Sign into the real site in
its Android WebView, run a search, then **Study on canvas**.
The same saved library and one-at-a-time answers work locally, without this relay, an
extension, or another device. Ordinary browser CORS is enforced. A read-only
metadata request checks the authenticated connection before any answer is sent;
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

Imported questions are not graded locally and no answer key is guessed. Multiple sets and draft choices persist on device. Approve one answer at a time
with **Check with Assaad**, while Edge is connected and Noti is foregrounded. Nothing
queues up to send later or in a run. A valid Assaad login in Edge is
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

With Docker running, `bun run check:deployment` tests the production Dokploy
Compose through an isolated local Traefik fixture. It checks health, code pairing,
origin rules, bidirectional WebSockets, non-root runtime, SQLite persistence across
container replacement, and forwarded-IP spoofing/rate limits. Its random test
project, loopback port and synthetic database are removed afterwards; no real
Assaad requests or production volumes are used.

Tests use isolated SQLite databases and loopback WebSockets. Production clients
keep the fixed HTTPS domain. Coverage includes code expiry/reuse, role boundaries,
stale/inactive tabs, duplicate commands, reconnect, revocation, and persistence.
`bun run integration` in `extensions/assaad-reader-dev` exercises the actual DOM
extractor and answer handler through the running relay with a simulated app peer.
