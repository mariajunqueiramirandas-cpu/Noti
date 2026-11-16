import { afterEach, describe, expect, test } from "bun:test";
import { mkdtempSync, rmSync } from "node:fs";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { startServer } from "../src/index";
import { createApp } from "../src/app";
import { CODE_TTL, COMMAND_TTL, Store } from "../src/store";
import { batch as batchSchema } from "../src/protocol";

test("the shared Android fixture conforms to the relay protocol", async () => {
  const fixture = await Bun.file(new URL("../../protocol/fixtures/assaad-batch.json", import.meta.url)).json();
  expect(batchSchema.safeParse(fixture).success).toBe(true);
});

export const sampleBatch = () => ({
  batchId: crypto.randomUUID(), capturedAt: new Date().toISOString(),
  source: { url: "https://app.plataformaassaad.com.br/banco-de-questoes/explorar?category_id=567", page: "1" },
  questions: [{ id: "q-1", source: "Original test question", prompt: "Which river flows through Egypt?", tags: ["History"], difficulty: "Easy", images: [],
    alternatives: [{ id: "a-1", label: "A", text: "Nile", images: [] }, { id: "a-2", label: "B", text: "Amazon", images: [] }] }]
});
type Message = { type: string; payload: any };
class Peer {
  socket: WebSocket;
  messages: Message[] = [];
  waiters: Array<() => void> = [];
  constructor(url: string, token: string) {
    this.socket = new WebSocket(url);
    this.socket.onopen = () => this.socket.send(JSON.stringify({ type: "authenticate", token }));
    this.socket.onmessage = event => { this.messages.push(JSON.parse(String(event.data))); this.waiters.forEach(wake => wake()); };
  }
  send(type: string, payload?: unknown) { this.socket.send(JSON.stringify({ type, ...(payload === undefined ? {} : { payload }) })); }
  next(type: string, predicate: (payload: any) => boolean = () => true): Promise<any> {
    return new Promise((resolve, reject) => {
      const inspect = () => {
        const index = this.messages.findIndex(message => message.type === type && predicate(message.payload));
        if (index < 0) return;
        clearTimeout(timer); this.waiters = this.waiters.filter(wake => wake !== inspect);
        resolve(this.messages.splice(index, 1)[0].payload);
      };
      const timer = setTimeout(() => { this.waiters = this.waiters.filter(wake => wake !== inspect); reject(new Error(`Missing ${type}`)); }, 10000);
      this.waiters.push(inspect); inspect();
    });
  }
}
const runtimes: ReturnType<typeof startServer>[] = [];
const sockets: WebSocket[] = [];
afterEach(async () => { sockets.splice(0).forEach(socket => socket.close()); await Bun.sleep(10); runtimes.splice(0).forEach(runtime => runtime.stop()); });
async function paired() {
  const runtime = startServer({ databasePath: ":memory:", port: 0 }); runtimes.push(runtime);
  const base = `http://127.0.0.1:${runtime.server.port}`;
  const appCredentials = await fetch(base + "/v1/pairings/create", { method: "POST" }).then(response => response.json()) as any;
  const browserCredentials = await fetch(base + "/v1/pairings/join", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code: appCredentials.code }) }).then(response => response.json()) as any;
  const open = (token: string) => { const peer = new Peer(base.replace("http:", "ws:") + "/v1/stream", token); sockets.push(peer.socket); return peer; };
  const app = open(appCredentials.token); await app.next("session");
  const browser = open(browserCredentials.token); await browser.next("session");
  browser.send("presence", { active: true, path: "/banco-de-questoes/explorar" });
  await app.next("session", payload => payload.presence.active);
  return { runtime, base, appCredentials, browserCredentials, app, browser, open };
}

describe("code pairing and credentials", () => {
  test("six-digit codes expire, are consumed once, and grant separate roles", () => {
    let now = Date.now(); const store = new Store(":memory:", () => now);
    const created = store.create(); expect(created.code).toMatch(/^\d{6}$/);
    const joined = store.join(created.code)!; expect(joined.token).not.toBe(created.token);
    expect(store.auth(created.token)?.role).toBe("app"); expect(store.auth(joined.token)?.role).toBe("browser");
    expect(store.join(created.code)).toBeNull();
    const expired = store.create(); now += CODE_TTL + 1; expect(store.join(expired.code)).toBeNull();
    expect(JSON.stringify(store.db.query("SELECT * FROM rooms").all())).not.toContain(created.token);
    store.remove(created.sessionId); expect(store.auth(joined.token)).toBeNull(); store.db.close();
  });
  test("pairing attempts are bounded and page origins cannot call the API", async () => {
    const store = new Store(":memory:"); const app = createApp(store);
    expect((await app.request("/v1/pairings/create", { method: "POST", headers: { Origin: "https://app.plataformaassaad.com.br" } })).status).toBe(403);
    expect((await app.request("/v1/session")).status).toBe(401);
    for (let i = 0; i < 10; i++) expect((await app.request("/v1/pairings/join", { method: "POST", body: '{"code":"000000"}', headers: { "Content-Type": "application/json" } })).status).toBe(400);
    expect((await app.request("/v1/pairings/join", { method: "POST" })).status).toBe(429); store.db.close();
  });
});
test("presence, questions, answers and official feedback complete the relay round trip", async () => {
  const { app, browser } = await paired();
  const batch = sampleBatch(); browser.send("questions", batch);
  expect((await browser.next("accepted")).count).toBe(1); expect((await app.next("questions")).questions[0].id).toBe("q-1");
  const answer = { commandId: crypto.randomUUID(), batchId: batch.batchId, questionId: "q-1", alternativeId: "a-1" };
  app.send("answer", answer); expect(await browser.next("answer")).toEqual(answer);
  const result = { commandId: answer.commandId, status: "confirmed", correct: true, correctAlternativeId: "a-1", feedback: "Resposta correta." };
  browser.send("answer-result", result); expect(await app.next("answer-result")).toMatchObject({ ...result, questionId: "q-1", alternativeId: "a-1", batchId: batch.batchId });
  app.send("answer", answer); expect(await app.next("answer-result")).toMatchObject(result);
  expect(browser.messages.filter(message => message.type === "answer")).toHaveLength(0);
});
test("role, stale-batch and alternative checks prevent unrelated page actions", async () => {
  const { app, browser } = await paired(); const batch = sampleBatch();
  app.send("questions", batch); expect((await app.next("error")).error).toBe("wrong_role");
  browser.send("questions", batch); await browser.next("accepted"); await app.next("questions");
  const value = { commandId: crypto.randomUUID(), batchId: crypto.randomUUID(), questionId: "q-1", alternativeId: "a-1" };
  app.send("answer", value); expect((await app.next("error")).error).toBe("stale_question");
  app.send("answer", { ...value, commandId: crypto.randomUUID(), batchId: batch.batchId, alternativeId: "not-a-choice" });
  expect((await app.next("error")).error).toBe("stale_question");
  browser.send("answer", value); expect((await browser.next("error")).error).toBe("wrong_role");
  browser.send("questions", { ...batch, questions: [{ ...batch.questions[0], answerKey: "secret" }] });
  expect((await browser.next("error")).error).toBe("invalid_message");
});
test("reconnect replays a stored batch and pending command; revoked tokens cannot reconnect", async () => {
  const { base, app, browser, browserCredentials, appCredentials, open } = await paired();
  const batch = sampleBatch(); browser.send("questions", batch); await browser.next("accepted"); await app.next("questions");
  const value = { commandId: crypto.randomUUID(), batchId: batch.batchId, questionId: "q-1", alternativeId: "a-2" };
  app.send("answer", value); await browser.next("answer"); browser.socket.close(); await Bun.sleep(20);
  const nextBrowser = open(browserCredentials.token); await nextBrowser.next("session"); expect(await nextBrowser.next("answer")).toEqual(value);
  const nextApp = open(appCredentials.token); await nextApp.next("session"); expect((await nextApp.next("questions")).batchId).toBe(batch.batchId);
  expect((await fetch(base + "/v1/session", { method: "DELETE", headers: { Authorization: `Bearer ${appCredentials.token}` } })).status).toBe(200);
  expect((await fetch(base + "/v1/session", { headers: { Authorization: `Bearer ${browserCredentials.token}` } })).status).toBe(401);
});
test("session isolation and inactive browser prevent cross-session or accidental submissions", async () => {
  const one = await paired(), two = await paired(); const batch = sampleBatch();
  one.browser.send("questions", batch); await one.browser.next("accepted"); await one.app.next("questions");
  const command = { commandId: crypto.randomUUID(), batchId: batch.batchId, questionId: "q-1", alternativeId: "a-1" };
  two.app.send("answer", command); expect((await two.app.next("error")).error).toBe("stale_question");
  one.browser.send("presence", { active: false, path: null }); await one.app.next("session", value => !value.presence.active);
  one.app.send("answer", command); expect((await one.app.next("error")).error).toBe("browser_offline");
});
test("SQLite keeps tokens and batches over a restart; old commands are not replayed", () => {
  const directory = mkdtempSync(join(tmpdir(), "noti-relay-")); const path = join(directory, "test.sqlite");
  let now = Date.now(); let store = new Store(path, () => now);
  const credentials = store.create(); const batch = sampleBatch(); store.saveBatch(credentials.sessionId, batch);
  const command = { commandId: crypto.randomUUID(), batchId: batch.batchId, questionId: "q-1", alternativeId: "a-1" };
  store.saveCommand(credentials.sessionId, command); store.db.close(); store = new Store(path, () => now);
  expect(store.auth(credentials.token)?.role).toBe("app"); expect(store.batch(credentials.sessionId)?.batchId).toBe(batch.batchId);
  expect(store.pending(credentials.sessionId)).toHaveLength(1); now += COMMAND_TTL + 1; expect(store.pending(credentials.sessionId)).toHaveLength(0);
  store.db.close();
  if (!resolve(directory).startsWith(resolve(tmpdir(), "noti-relay-"))) throw new Error("Unexpected test directory");
  rmSync(directory, { recursive: true });
});
