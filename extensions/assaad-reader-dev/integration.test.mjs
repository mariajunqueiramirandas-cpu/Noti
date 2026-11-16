import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { Window } from "happy-dom";
import { startServer } from "../../server/src/index.ts";
import { RelayClient } from "../assaad-reader/transport.js";
import { answerOnPage } from "../assaad-reader/answer.js";
import { fixture } from "./fixture.mjs";

test("rendered browser questions reach Noti and its explicit answer returns official UI feedback", async () => {
  const runtime = startServer({ databasePath: ":memory:", port: 0 });
  const base = `http://127.0.0.1:${runtime.server.port}`;
  let client, app;
  try {
    const created = await fetch(base + "/v1/pairings/create", { method: "POST" }).then(r => r.json());
    const joined = await fetch(base + "/v1/pairings/join", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code: created.code }) }).then(r => r.json());
    const window = new Window({ url: "https://app.plataformaassaad.com.br/banco-de-questoes/explorar" }); window.document.write(fixture());
    const reader = readFileSync(new URL("../assaad-reader/reader.js", import.meta.url), "utf8").replace("export function", "function");
    const read = () => JSON.parse(JSON.stringify(window.eval(reader + "\nreadQuestions();")));
    const snapshot = read(); const expected = snapshot.questions[0];
    const confirm = [...window.document.querySelectorAll("button")].find(e => e.textContent === "Confirmar resposta");
    confirm.setAttribute("aria-label", "Confirmar resposta"); confirm.disabled = true;
    const feedback = window.document.createElement("div"); feedback.setAttribute("aria-live", "polite"); confirm.parentElement.append(feedback);
    const radio = window.document.getElementById(expected.alternatives[0].id);
    let submissions = 0;
    radio.addEventListener("click", () => { radio.setAttribute("aria-checked", "true"); confirm.disabled = false; });
    confirm.addEventListener("click", () => { submissions++; feedback.textContent = "Resposta correta."; confirm.disabled = true; });
    const received = [];
    app = new WebSocket(base.replace("http:", "ws:") + "/v1/stream");
    app.onopen = () => app.send(JSON.stringify({ type: "authenticate", token: created.token }));
    app.onmessage = event => received.push(JSON.parse(String(event.data)));
    client = new RelayClient({ token: joined.token, base,
      onState(state) { if (state === "connected") client.send("presence", { active: true, path: "/banco-de-questoes/explorar" }); },
      onMessage(message) { if (message.type === "answer") void answerOnPage(message.payload, expected, read, window.document, async () => {}).then(result => client.send("answer-result", result)); }
    });
    // The application under test reports every session update, so publish once
    // after the handshake; no status-to-presence echo is needed after that.
    let initialized = false;
    client.onState = state => { if (state === "connected" && !initialized) { initialized = true; client.send("presence", { active: true, path: "/banco-de-questoes/explorar" }); } };
    client.connect();
    const waitFor = async predicate => { for (let i = 0; i < 500; i++) { if (predicate()) return; await Bun.sleep(10); } throw new Error("Relay integration timed out"); };
    await waitFor(() => received.some(m => m.type === "session" && m.payload.presence.active));
    const batch = { batchId: crypto.randomUUID(), capturedAt: snapshot.capturedAt, source: snapshot.source, questions: snapshot.questions };
    await client.publish(batch);
    await waitFor(() => received.some(m => m.type === "questions"));
    expect(received.find(m => m.type === "questions").payload.questions[0].prompt).toContain("Qual rio");
    expect(submissions).toBe(0);
    const commandId = crypto.randomUUID();
    app.send(JSON.stringify({ type: "answer", payload: { commandId, batchId: batch.batchId, questionId: expected.id, alternativeId: expected.alternatives[0].id } }));
    await waitFor(() => received.some(m => m.type === "answer-result"));
    expect(received.find(m => m.type === "answer-result").payload).toMatchObject({ commandId, status: "confirmed", correct: true });
    expect(submissions).toBe(1);
  } finally { client?.stop(); app?.close(); await Bun.sleep(10); runtime.stop(); }
}, 20000);


test("saved API questions submit after a newer import without the original page and duplicates do not write twice", async () => {
  const { harness, imported, command, official } = await import("./api-fixture.mjs");
  const runtime = startServer({ databasePath: ":memory:", port: 0 });
  const base = `http://127.0.0.1:${runtime.server.port}`;
  let app, client;
  try {
    const created = await fetch(base + "/v1/pairings/create", { method: "POST" }).then(r => r.json());
    const joined = await fetch(base + "/v1/pairings/join", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code: created.code }) }).then(r => r.json());
    const received = []; const api = harness((_url, options) => options.method === "PATCH" ? new Response(JSON.stringify(official())) : null);
    const saved = await imported();
    app = new WebSocket(base.replace("http:", "ws:") + "/v1/stream");
    app.onopen = () => app.send(JSON.stringify({ type: "authenticate", token: created.token }));
    app.onmessage = event => received.push(JSON.parse(String(event.data)));
    client = new RelayClient({ token: joined.token, base, onState() {}, onMessage(message) {
      if (message.type === "answer") void api.run({ operation: "answer", batch: message.payload.batch, command: message.payload }).then(result => client.send("answer-result", result));
    } });
    client.connect();
    const waitFor = async predicate => { for (let i = 0; i < 500; i++) { if (predicate()) return; await Bun.sleep(10); } throw Error("API bridge timed out"); };
    await waitFor(() => received.some(m => m.type === "session" && m.payload.peerOnline));
    client.send("presence", { active: false, path: null, ready: true, accountRef: saved.accountRef });
    await client.publish({ ...saved, batchId: crypto.randomUUID() });
    app.send(JSON.stringify({ type: "restore-questions", payload: saved }));
    await waitFor(() => received.some(m => m.type === "restored"));
    app.send(JSON.stringify({ type: "answer", payload: command() }));
    await waitFor(() => received.some(m => m.type === "answer-result"));
    expect(received.find(m => m.type === "answer-result").payload).toMatchObject({ status: "confirmed", correct: true, batchId: saved.batchId });
    app.send(JSON.stringify({ type: "answer", payload: command() }));
    await waitFor(() => received.filter(m => m.type === "answer-result").length === 2);
    expect(api.calls.filter(c => c.method === "PATCH")).toHaveLength(1);
  } finally { client?.stop(); app?.close(); await Bun.sleep(10); runtime.stop(); }
}, 20000);
