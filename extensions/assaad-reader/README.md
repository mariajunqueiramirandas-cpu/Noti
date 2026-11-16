# Noti × Plataforma Assaad

Manifest V3 extension for Microsoft Edge and Chrome. The popup is used only for
pairing with Noti; its white/gray cards and blue controls follow Assaad's UI.
The default relay is **https://noti.vendua.com.br**.

## Install / update in Edge

1. Deploy the server first using the root `compose.yaml` and `server/README.md`.
2. Open `edge://extensions`, enable Developer mode, then **Load unpacked** and
   choose `C:\Noti\extensions\assaad-reader` (the folder containing `manifest.json`).
   If this folder is already installed, use **Reload**. Version 2 needs access to
   Assaad and the relay; accept Edge's updated site permissions if prompted.
3. Refresh your Assaad tab after installing/reloading.
4. In Noti's library, choose **Plataforma Assaad → Generate pairing code**.
5. Click **Conectar ao Noti** on Assaad's dashboard or open the extension popup,
   then enter the six-digit code displayed by Noti.
6. In a question search, click **Enviar questões ao Noti**. Open the canvas in Noti,
   select an option, and use **Confirm in Assaad** to submit it on the website.

Chrome uses the same steps at `chrome://extensions`. Pairing codes expire after
five minutes and can be used once. Disconnecting either device ends the session
for both. Pairing persists through browser/server restarts, for up to 30 days.

## What is shared

After pairing, the browser automatically sends whether Assaad is active and the
current page category. Questions are read **only when you click send**. That sends
the currently rendered page, including text, alternatives, metadata and image
references. There is no automatic pagination or fetching of hidden answer keys.
Noti receives official feedback only after you explicitly confirm an answer.
The extension doesn't call Assaad's private APIs; confirmation uses the normal
page controls, changes your platform answer, and may be recorded by the platform.
There is no stealth or detection/logging guarantee.

Relay traffic uses HTTPS/WSS. Separate device tokens live in extension-local
storage restricted to trusted extension contexts. Tokens and cookies are never
inserted into page DOM or sent as WebSocket URL parameters. No browser history,
credentials, account profile data, drawings, or unrelated website content is read.
Drawings stay on the tablet. The server retains the latest question batch for
reconnection; full retention/deployment details are in `server/README.md`.

The reader supports the observed Assaad card layout. It excludes filters, hidden
content and explanations, preserves rendered KaTeX as LaTeX, and skips unsupported
cards. Navigating away, changing a question, already-confirmed controls, or losing
the original tab prevents submission. Interrupted commands are not automatically
repeated; check the browser if a confirmation has an unknown outcome.

## Build and test

Bun is needed only to build/test, not to run the installed extension. A generated
`content.bundle.js` is included so the folder is immediately loadable.

```powershell
cd C:\Noti\server
bun install --frozen-lockfile
bun test
cd C:\Noti\extensions\assaad-reader-dev
npm ci
npm test
bun run integration
.\package.ps1
```

The package is written to `work\artifacts\assaad-reader-2.0.0.zip`; extract it
before **Load unpacked**. Tests/dependencies stay outside the package. CI checks
the bundle against its sources and publishes a ZIP artifact on the PR.
