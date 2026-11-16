import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
const code = readFileSync(new URL("../assaad-reader/background.js", import.meta.url), "utf8").replace(/^import .*;\r?\n/gm, "");
const flush = () => new Promise(resolve => setImmediate(resolve));
async function setup(initial = {}) {
  let listener, client, dispatches = 0; const storage = structuredClone(initial); const sent = [];
  const event = { addListener() {} };
  const chrome = {
    runtime: { getURL: path => "chrome-extension://test/" + path, onMessage: { addListener: fn => { listener = fn; } } },
    storage: { local: { setAccessLevel: async () => {}, get: async () => storage, set: async value => Object.assign(storage, value), remove: async keys => keys.forEach(key => delete storage[key]) } },
    alarms: { create: async () => {}, clear: async () => {}, onAlarm: event },
    tabs: { query: async () => [{ id: 7, url: "https://app.plataformaassaad.com.br/banco-de-questoes/explorar" }], sendMessage: async (_id, message) => { dispatches++; return { commandId: message.command.commandId, status: "confirmed", correct: true, correctAlternativeId: "a", feedback: "Correct" }; }, onActivated: event, onUpdated: event, onRemoved: event },
    windows: { onFocusChanged: event }, action: { openPopup: async () => {} }
  };
  class RelayClient {
    constructor(options) { client = this; this.options = options; }
    connect() { this.options.onState("connected", { peerOnline: true }); }
    send(type, payload) { sent.push({ type, payload }); return true; }
    stop() {}
  }
  vm.runInNewContext(code, { chrome, RelayClient, SERVER: "https://noti.vendua.com.br", URL, fetch }); await flush();
  const request = (message, sender = { url: "chrome-extension://test/popup.html" }) => new Promise(resolve => listener(message, sender, resolve));
  return { storage, sent, client, request, dispatches: () => dispatches };
}
test("background rejects foreign senders and does not loop presence on status updates", async () => {
  const app = await setup({ pairing: { token: "test" } });
  const reply = await app.request({ type: "NOTI_STATUS" }, { url: "https://evil.example/", tab: { id: 2 } }); assert.equal(reply.error, "untrusted_sender");
  assert.equal((await app.request({ type: "NOTI_STATUS" }, { url: "chrome-extension://test/popup.html", tab: { id: 8 } })).paired, true);
  const count = app.sent.filter(message => message.type === "presence").length;
  app.client.options.onState("connected", { peerOnline: true }); await flush();
  assert.equal(app.sent.filter(message => message.type === "presence").length, count);
});
test("duplicate commands submit once and interrupted commands report uncertainty", async () => {
  const initial = { pairing: { token: "test" }, source: { tabId: 7, batchId: "batch", questions: [{ id: "q", alternatives: [{ id: "a" }] }] } };
  const app = await setup(initial); const payload = { commandId: "command", batchId: "batch", questionId: "q", alternativeId: "a" };
  app.client.options.onMessage({ type: "answer", payload }); await flush();
  app.client.options.onMessage({ type: "answer", payload }); await flush(); assert.equal(app.dispatches(), 1);
  const interrupted = await setup({ ...initial, commands: { command: { executing: true } } });
  interrupted.client.options.onMessage({ type: "answer", payload }); await flush();
  assert.equal(interrupted.dispatches(), 0); assert.equal(interrupted.sent.find(message => message.type === "answer-result").payload.status, "unknown");
});
