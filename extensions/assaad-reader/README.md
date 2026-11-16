# Noti · Plataforma Assaad — version 3

Edge/Chrome Manifest V3 extension. The popup is exclusively six-digit pairing,
using Assaad’s white/gray cards and blue controls. Server: **https://noti.vendua.com.br**.

## Install or update

Deploy the root Compose file using [the server guide](../../server/README.md).
In `edge://extensions`, load unpacked `C:\Noti\extensions\assaad-reader`, or reload
an existing installation. Version 3 adds `scripting` on the existing Assaad host;
site access stays restricted to Assaad and the relay. Refresh the website afterwards.
In Noti, open Plataforma Assaad and generate a pairing code. Enter it in the popup
or via **Conectar ao Noti** on the dashboard. Codes expire in five minutes.

## Save and answer later

Click **Enviar questões ao Noti** to import only currently displayed question IDs
through the ordinary search API. Noti keeps multiple saved sets and offline drafts.
Use **Confirm in Assaad** for one answer or **Saved sets → Submit saved answers**
for the saved drafts. The queue supports pause/resume/cancel and runs while Noti is
foregrounded. Ink stays in the native local canvas store.

Edge must be running and logged into the original Assaad account. The extension
reuses a tab or opens one background tab for an approved submission; the original
question page is unnecessary for API-backed sets. Old DOM-only imports still need
the original page; reimport with version 3 to upgrade them. Pairing lasts 30 days;
re-pairing or disconnecting does not erase the tablet’s saved library.

## One tablet without Edge

In Noti, choose **Plataforma Assaad → This tablet** to use its real embedded
browser. Sign into the site, **Save questions**, then **Canvas** to minimize it.
This mode needs neither the extension nor the relay. The shared adapter runs in
the real site origin with standard CORS; a failed connection check blocks writes.
Live Assaad WebView login/CORS behavior remains unverified on a physical device.
You can switch connection modes while no answer is in flight; saved sets remain.

## Fair use and privacy

**Every Assaad API request originates inside the user’s own Edge page and network
connection. The Noti server never proxies those requests or holds Assaad credentials.**
A fixed packaged function runs through the Chrome scripting API. Tokens stay in
the page context; no debugger, cookie access, broad host access, or arbitrary URL
proxy is requested. VPN/proxy settings on the user’s connection determine its public IP.

Operations are serialized: at least five seconds between operations, two seconds
between requests within an operation, and at most six approved answers per minute.
HTTP 429 enforces Retry-After (at least 60 seconds) across extension restarts. No
pagination crawl, answer-key fetch, or automatic write retry is performed. These
are transparent limits for fair use, not measures to hide normal logged activity.

The relay receives bounded question snapshots, image references, account hashes,
activity categories, choices, and official feedback. Assaad tokens/cookies, raw
profiles, hidden solutions, and drawings are not shared. Before writing, the adapter
checks account and question content. An uncertain write pauses for user review.

Noti copies anonymous HTTPS images locally when possible (4 MiB each, 64 MiB total).
Saved sets shows the offline image count. Protected or oversized images may need
the browser. Text, formulas, drafts, and existing saved study material remain local.

See [the endpoint investigation](../../docs/integrations/plataforma-assaad-api.md)
for the source-confirmed contracts and unverified live behavior. Development tests
do not submit answers to a real Assaad account.

## Build and check

Run `bun install --frozen-lockfile`, `bun run typecheck`, and `bun test` in `server`.
In `extensions/assaad-reader-dev`, run `npm ci`, `npm test`, `bun run integration`,
and `./package.ps1`. The ZIP is `work/artifacts/assaad-reader-3.0.0.zip`.
Extract it before loading unpacked. Test dependencies are outside the runtime folder.
