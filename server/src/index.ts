import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { createApp } from "./app";
import { Relay, type SocketData } from "./relay";
import { Store } from "./store";

export function startServer(options: { databasePath?: string; port?: number; trustProxy?: boolean } = {}) {
const path = options.databasePath || process.env.DATABASE_PATH || "./data/noti.sqlite";
if (path !== ":memory:") mkdirSync(dirname(resolve(path)), { recursive: true });
const store = new Store(path);
const relay = new Relay(store);
const trustProxy = options.trustProxy ?? process.env.TRUST_PROXY === "true";
const app = createApp(store, {
  address: request => trustProxy ? request.headers.get("X-Real-IP") || "proxy" : server.requestIP(request)?.address || "unknown",
  notify: relay.notify, revoke: relay.revoke
});
const server = Bun.serve<SocketData>({
  port: options.port ?? Number(process.env.PORT || 3000), hostname: "0.0.0.0",
  fetch(request, server) {
    if (new URL(request.url).pathname === "/v1/stream") {
      const origin = request.headers.get("Origin");
      if (origin && !/^chrome-extension:\/\/[a-p]{32}$/.test(origin)) return new Response("Forbidden", { status: 403 });
      if (server.upgrade(request, { data: {} })) return;
      return new Response("WebSocket required", { status: 426 });
    }
    return app.fetch(request);
  },
  websocket: {
    maxPayloadLength: 1_048_576, idleTimeout: 60, backpressureLimit: 2_097_152,
    closeOnBackpressureLimit: true, sendPings: true,
    open: socket => relay.open(socket), message: (socket, message) => relay.message(socket, message), close: socket => relay.close(socket)
  }
});
const cleanup = setInterval(() => { store.cleanup(); }, 60_000);
cleanup.unref();
return { server, store, relay, stop: () => { clearInterval(cleanup); server.stop(true); } };
}
if (import.meta.main) {
  const { server } = startServer();
  console.info(`Noti relay listening on port ${server.port}`);
}
