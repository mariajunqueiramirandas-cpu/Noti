import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";

// An isolated Docker project, random loopback port and synthetic data only.
// The fixture proxy replaces the domain routing Dokploy normally injects.
const root = fileURLToPath(new URL("../../", import.meta.url));
const project = "noti-dokploy-check-" + crypto.randomUUID().slice(0, 8);
const compose = ["docker", "compose", "--project-name", project,
  "-f", "server/compose.yml", "-f", "server/test/dokploy.compose.yml"];
async function docker(args: string[], output = false) {
  const process = Bun.spawn(args, { cwd: root, stdout: "pipe", stderr: "pipe" });
  const [stdout, stderr, code] = await Promise.all([
    new Response(process.stdout).text(), new Response(process.stderr).text(), process.exited
  ]);
  if (output) { if (stdout) console.log(stdout.trim()); if (stderr) console.log(stderr.trim()); }
  if (code !== 0) throw new Error(`${args.join(" ")} failed (${code})\n${stdout}\n${stderr}`);
  return stdout.trim();
}

class Peer {
  readonly socket: WebSocket;
  private messages: Array<{ type: string; payload: any }> = [];
  private listeners = new Set<() => void>();
  constructor(url: string, token: string) {
    // Bun supports handshake headers; TypeScript's DOM overload omits that option.
    const Client = WebSocket as unknown as new (url: string, options: Bun.WebSocketOptions) => WebSocket;
    this.socket = new Client(url, { headers: {
      Host: "noti.vendua.com.br", Origin: "chrome-extension://" + "a".repeat(32)
    } });
    this.socket.onopen = () => this.socket.send(JSON.stringify({ type: "authenticate", token }));
    this.socket.onmessage = event => {
      this.messages.push(JSON.parse(String(event.data))); this.listeners.forEach(listener => listener());
    };
    this.socket.onerror = () => {};
  }
  send(type: string, payload: unknown) { this.socket.send(JSON.stringify({ type, payload })); }
  next(type: string, predicate: (payload: any) => boolean = () => true): Promise<any> {
    return new Promise((resolve, reject) => {
      const inspect = () => {
        const index = this.messages.findIndex(message => message.type === type && predicate(message.payload));
        if (index < 0) return;
        clearTimeout(timer); this.listeners.delete(inspect); resolve(this.messages.splice(index, 1)[0].payload);
      };
      const timer = setTimeout(() => { this.listeners.delete(inspect); reject(new Error("No proxied WebSocket " + type)); }, 10_000);
      this.listeners.add(inspect); inspect();
    });
  }
  close() { this.socket.close(); }
}

const peers: Peer[] = [];
try {
  const config = JSON.parse(await docker(["docker", "compose", "-f", "server/compose.yml", "config", "--format", "json"]));
  assert.deepEqual(Object.keys(config.services), ["relay"]);
  assert.equal(config.services.relay.ports, undefined, "Dokploy owns published ports");
  assert.equal(config.services.relay.read_only, true);
  assert.equal(config.services.relay.environment.TRUST_PROXY, "true");
  assert.ok(config.services.relay.volumes.some((v: any) => v.type === "volume" && v.target === "/data"));
  await docker([...compose, "up", "-d", "--build", "--wait", "--wait-timeout", "120"], true);
  const address = await docker([...compose, "port", "traefik", "8080"]);
  const base = "http://" + address;
  async function request(path: string, init: RequestInit = {}) {
    const headers = new Headers(init.headers); headers.set("Host", "noti.vendua.com.br");
    return fetch(base + path, { ...init, headers, signal: AbortSignal.timeout(10_000) });
  }
  async function successful(path: string, init: RequestInit = {}) {
    const response = await request(path, init); assert.ok(response.ok, `${path}: HTTP ${response.status}`);
    return response.json() as Promise<any>;
  }
  assert.equal((await successful("/health")).protocol, 1);
  assert.equal((await request("/health", { headers: { Origin: "https://app.plataformaassaad.com.br" } })).status, 403);
  const appCredentials = await successful("/v1/pairings/create", { method: "POST" });
  const browserCredentials = await successful("/v1/pairings/join", {
    method: "POST", headers: { "Content-Type": "application/json", Origin: "chrome-extension://" + "a".repeat(32) },
    body: JSON.stringify({ code: appCredentials.code })
  });
  const open = (token: string) => {
    const peer = new Peer(base.replace("http:", "ws:") + "/v1/stream", token); peers.push(peer); return peer;
  };
  const app = open(appCredentials.token); await app.next("session");
  const browser = open(browserCredentials.token); await browser.next("session");
  browser.send("presence", { active: true, path: "/banco-de-questoes/explorar" });
  await app.next("session", payload => payload.presence.active);
  const batch = await Bun.file(new URL("../../protocol/fixtures/assaad-batch.json", import.meta.url)).json();
  batch.batchId = crypto.randomUUID(); batch.capturedAt = new Date().toISOString();
  browser.send("questions", batch); await browser.next("accepted");
  assert.equal((await app.next("questions")).batchId, batch.batchId);
  const answer = { commandId: crypto.randomUUID(), batchId: batch.batchId,
    questionId: batch.questions[0].id, alternativeId: batch.questions[0].alternatives[0].id };
  app.send("answer", answer);
  assert.equal((await browser.next("answer")).batch.batchId, batch.batchId);
  browser.send("answer-result", { commandId: answer.commandId, status: "confirmed", correct: true,
    correctAlternativeId: answer.alternativeId, feedback: "Synthetic deployment check" });
  assert.equal((await app.next("answer-result")).commandId, answer.commandId);
  peers.splice(0).forEach(peer => peer.close());
  console.log("PASS: health, code pairing, origin checks and bidirectional WebSockets through Traefik");

  // A new container must retain the same SQLite pairing/archive, not just a process restart.
  await docker([...compose, "up", "-d", "--no-deps", "--force-recreate", "--wait", "--wait-timeout", "60", "relay"], true);
  const restored = await successful("/v1/session", { headers: { Authorization: "Bearer " + appCredentials.token } });
  assert.equal(restored.sessionId, appCredentials.sessionId);
  const reconnected = open(appCredentials.token); await reconnected.next("session");
  assert.equal((await reconnected.next("questions")).batchId, batch.batchId);
  assert.equal((await reconnected.next("answer-result")).commandId, answer.commandId);
  assert.notEqual(await docker([...compose, "exec", "-T", "relay", "id", "-u"]), "0");
  console.log("PASS: non-root runtime and SQLite pairing/questions/results survive container replacement");

  // Traefik must replace untrusted forwarded IPs; changing headers cannot reset the IP limiter.
  for (let i = 0; i <= 10; i++) {
    const response = await request("/v1/pairings/join", { method: "POST", headers: {
      "Content-Type": "application/json", "X-Real-IP": `198.51.100.${i + 1}`, "X-Forwarded-For": `203.0.113.${i + 1}`
    }, body: JSON.stringify({ code: "invalid" }) });
    assert.equal(response.status, i < 10 ? 400 : 429, "Proxy must preserve client-IP rate limits");
  }
  console.log("PASS: proxy client-IP sanitization and pairing rate limit");
} catch (error) {
  await docker([...compose, "logs", "--tail", "60"], true).catch(() => {});
  throw error;
} finally {
  peers.forEach(peer => peer.close());
  // Only the uniquely named test project and its synthetic SQLite volume are removed.
  await docker([...compose, "down", "--volumes", "--remove-orphans"], true);
}
