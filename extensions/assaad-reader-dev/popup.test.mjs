import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { Window } from "happy-dom";
const root = new URL("../assaad-reader/", import.meta.url);
const html = readFileSync(new URL("popup.html", root), "utf8").replace(/<script[^>]*><\/script>/g, "");
const code = readFileSync(new URL("popup.js", root), "utf8");
const flush = () => new Promise(resolve => setImmediate(resolve));
function setup(handler) {
  const window = new Window({ url: "https://extension.example/popup.html" }); window.document.write(html);
  const calls = []; window.setInterval = () => 1;
  window.chrome = { runtime: { sendMessage: async message => { calls.push(message); return handler(message); } } };
  window.eval(code); return { window, document: window.document, calls };
}
test("popup is exclusively pairing UI, matching the server default", async () => {
  const { document, calls } = setup(() => ({ paired: false, connection: "disconnected" })); await flush();
  assert.deepEqual(calls.map(call => call.type), ["NOTI_STATUS"]);
  assert.equal(document.getElementById("pair-view").hidden, false);
  assert.equal(document.getElementById("connected-view").hidden, true);
  assert.equal(document.querySelectorAll('#read, #search, #questions, #json, #txt').length, 0);
  assert.match(document.querySelector("footer").textContent, /noti.vendua.com.br/);
});
test("pairing sends just the six-digit code, clears it, and shows connected status", async () => {
  const app = setup(message => ({ paired: message.type === "NOTI_PAIR", connection: "connected", peerOnline: true })); await flush();
  app.document.getElementById("code").value = "123456";
  app.document.getElementById("pair-form").dispatchEvent(new app.window.Event("submit", { cancelable: true })); await flush();
  assert.deepEqual(JSON.parse(JSON.stringify(app.calls[1])), { type: "NOTI_PAIR", code: "123456" });
  assert.equal(app.document.getElementById("code").value, "");
  assert.equal(app.document.getElementById("connected-view").hidden, false);
  assert.match(app.document.getElementById("peer").textContent, /Noti online/);
});
test("expired codes give actionable feedback; disconnect keeps pairing on server failure", async () => {
  const app = setup(message => message.type === "NOTI_STATUS" ? { paired: false } : { error: "expired_or_used_code" }); await flush();
  app.document.getElementById("pair-form").dispatchEvent(new app.window.Event("submit", { cancelable: true })); await flush();
  assert.match(app.document.getElementById("status").textContent, /Gere outro/);
  assert.equal(app.document.getElementById("pair").disabled, false);
  const paired = setup(message => message.type === "NOTI_STATUS" ? { paired: true, connection: "connected" } : { error: "disconnect_failed" }); await flush();
  paired.document.getElementById("disconnect").click(); await flush();
  assert.equal(paired.document.getElementById("connected-view").hidden, false);
  assert.match(paired.document.getElementById("status").textContent, /Tente novamente/);
});
