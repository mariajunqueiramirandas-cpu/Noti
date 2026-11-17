import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { Window } from "happy-dom";
import { fixture } from "./fixture.mjs";
const code = readFileSync(new URL("../assaad-reader/content.bundle.js", import.meta.url), "utf8");
const flush = () => new Promise(resolve => setImmediate(resolve));
function setup(path, paired = false, handler = null, searchButton = false) {
  const window = new Window({ url: "https://app.plataformaassaad.com.br" + path }); window.document.write(fixture());
  const main = window.document.createElement("main");
  while (window.document.body.firstChild) main.append(window.document.body.firstChild);
  window.document.body.append(main);
  if (searchButton) { const actions = window.document.createElement("div"); actions.id = "search-actions"; actions.innerHTML = "<button>Buscar questões</button>"; main.append(actions); }
  const calls = []; window.setInterval = () => 1;
  window.chrome = { runtime: { onMessage: { addListener() {} }, sendMessage: async message => {
    calls.push(message);
    if (handler) return handler(message);
    return message.type === "NOTI_SEND_QUESTIONS" ? { count: message.batch.questions.length } : { paired, connection: "connected", peerOnline: true };
  } } };
  window.eval(code); return { window, document: window.document, calls };
}
test("dashboard connects without reading questions", async () => {
  const app = setup("/"); await flush();
  assert.equal(app.document.querySelector("#noti-assaad-connection button").textContent, "Conectar ao Noti");
  app.document.querySelector("#noti-assaad-connection button").click(); await flush();
  assert.ok(app.calls.some(message => message.type === "NOTI_OPEN_PAIRING"));
  assert.ok(!app.calls.some(message => message.type === "NOTI_SEND_QUESTIONS")); await app.window.happyDOM.close();
});
test("question send is explicit and includes current rendered content only", async () => {
  const app = setup("/banco-de-questoes/explorar", true); await flush();
  const button = app.document.querySelector("#noti-assaad-connection button");
  assert.equal(button.textContent, "Enviar questões ao Noti");
  assert.equal(app.calls.filter(message => message.type === "NOTI_SEND_QUESTIONS").length, 0);
  button.click(); await flush();
  const sent = app.calls.find(message => message.type === "NOTI_SEND_QUESTIONS");
  assert.equal(sent.batch.questions.length, 1);
  assert.equal(sent.batch.questions[0].alternatives[0].id, "sample-question-a");
  assert.doesNotMatch(JSON.stringify(sent), /SECRET|PRIVATE|EXPLANATION/);
  assert.match(app.document.querySelector("#noti-assaad-connection span").textContent, /1 questões enviadas/);
  await app.window.happyDOM.close();
});
test("unrelated platform routes have no question-send control", async () => {
  const app = setup("/materiais", true); await flush();
  assert.equal(app.document.querySelector("#noti-assaad-connection"), null); await app.window.happyDOM.close();
});

test("the real search button needs no aria-label and hosts the Noti control", async () => {
  const app = setup("/banco-de-questoes/explorar", true, null, true); await flush();
  assert.equal(app.document.getElementById("noti-assaad-connection").parentElement.id, "search-actions");
  await app.window.happyDOM.close();
});
test("an old connection error disappears when authoritative status reconnects", async () => {
  const app = setup("/banco-de-questoes/explorar", true, message => message.type === "NOTI_SEND_QUESTIONS" ? { error: "not_connected" } : { paired: true, connection: "connected", peerOnline: true }); await flush();
  app.document.querySelector("#noti-assaad-connection button").click(); await flush();
  assert.match(app.document.querySelector("#noti-assaad-connection span").textContent, /reconectando/);
  app.document.dispatchEvent(new app.window.Event("visibilitychange")); await flush();
  assert.equal(app.document.querySelector("#noti-assaad-connection span").textContent, "Canvas conectado");
  await app.window.happyDOM.close();
});
test("a replaced instance reconnects explicitly before any new question import", async () => {
  let connection = "replaced";
  const app = setup("/banco-de-questoes/explorar", true, message => {
    if (message.type === "NOTI_RECONNECT") connection = "connected";
    return { paired: true, connection, peerOnline: true };
  }); await flush();
  app.document.querySelector("#noti-assaad-connection button").click(); await flush();
  assert.equal(app.calls.some(m => m.type === "NOTI_RECONNECT"), true);
  assert.equal(app.calls.some(m => m.type === "NOTI_SEND_QUESTIONS"), false);
  assert.equal(app.document.querySelector("#noti-assaad-connection button").textContent, "Enviar questões ao Noti");
  await app.window.happyDOM.close();
});
