import { RelayClient, SERVER } from "./transport.js";
let client, pairing, source;
let commands = {}, connection = "disconnected", session = {}, answering = Promise.resolve();
const origin = "https://app.plataformaassaad.com.br";
const status = () => ({ paired: !!pairing, connection, peerOnline: !!session.peerOnline, presence: session.presence });
async function restore() {
  await chrome.storage.local.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" });
  ({ pairing, source, commands = {} } = await chrome.storage.local.get(["pairing", "source", "commands"]));
  // Never repeat a confirmation after an interrupted dispatch.
  for (const [id, value] of Object.entries(commands)) if (value.executing) commands[id] = uncertain(id);
  await chrome.storage.local.set({ commands });
  if (pairing) connect();
}
const ready = restore();
function uncertain(commandId) { return { commandId, status: "unknown", correct: null, correctAlternativeId: null, feedback: "A confirmação foi interrompida. Confira a questão no navegador antes de tentar novamente." }; }
function failed(commandId, feedback) { return { commandId, status: "failed", correct: null, correctAlternativeId: null, feedback }; }
function connect() {
  if (client || !pairing) return;
  client = new RelayClient({ token: pairing.token,
    onState: (state, value) => {
      const wasConnected = connection === "connected";
      connection = state; if (value) session = value;
      if (state === "connected" && !wasConnected) void updatePresence();
      if (state === "expired") void clearPairing();
    },
    onMessage: message => {
      if (message.type === "answer") answering = answering.then(() => answer(message.payload)).catch(() => {});
      if (message.type === "pong") void updatePresence();
    }
  });
  client.connect(); void chrome.alarms.create("noti-reconnect", { periodInMinutes: 1 });
}
async function clearPairing() {
  client?.stop(); client = undefined; pairing = undefined; source = undefined; commands = {};
  connection = "disconnected"; session = {};
  await chrome.storage.local.remove(["pairing", "source", "commands"]); await chrome.alarms.clear("noti-reconnect");
}
async function updatePresence() {
  if (!client || connection !== "connected") return;
  const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  let url; try { url = new URL(tab?.url); } catch { /* Other websites remain unread. */ }
  const active = url?.origin === origin;
  const path = !active ? null : url.pathname === "/" ? "/" : /^\/banco-de-questoes\/explorar\/?$/.test(url.pathname) ? "/banco-de-questoes/explorar" : "/other";
  client.send("presence", { active, path });
}
async function answer(command) {
  const old = commands[command.commandId];
  if (old) { client?.send("answer-result", old.executing ? uncertain(command.commandId) : old); return; }
  let result;
  const expected = source?.batchId === command.batchId && source.questions.find(q => q.id === command.questionId);
  if (!expected) result = failed(command.commandId, "Esta leitura não está mais aberta no navegador. Envie as questões novamente.");
  else {
    commands[command.commandId] = { executing: true }; await chrome.storage.local.set({ commands });
    try {
      result = await chrome.tabs.sendMessage(source.tabId, { type: "NOTI_ANSWER", command, expected });
      if (!result || result.commandId !== command.commandId) result = uncertain(command.commandId);
    } catch { result = uncertain(command.commandId); }
  }
  commands[command.commandId] = result; commands = Object.fromEntries(Object.entries(commands).slice(-200));
  await chrome.storage.local.set({ commands }); client?.send("answer-result", result);
}
async function handle(message, sender) {
  await ready;
  const fromPopup = sender.url?.startsWith(chrome.runtime.getURL("popup.html"));
  const fromSite = sender.tab && sender.url?.startsWith(origin + "/");
  if (!fromPopup && !fromSite) throw new Error("untrusted_sender");
  if (message.type === "NOTI_STATUS") return status();
  if (message.type === "NOTI_PAIR" && fromPopup) {
    if (!/^\d{6}$/.test(message.code || "")) throw new Error("invalid_code");
    if (pairing) throw new Error("already_paired");
    const response = await fetch(SERVER + "/v1/pairings/join", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code: message.code }) });
    const value = await response.json(); if (!response.ok) throw new Error(value.error || "connection_failed");
    pairing = value; await chrome.storage.local.set({ pairing }); connect(); return status();
  }
  if (message.type === "NOTI_DISCONNECT" && fromPopup) {
    if (pairing) {
      const response = await fetch(SERVER + "/v1/session", { method: "DELETE", headers: { Authorization: `Bearer ${pairing.token}` } });
      if (!response.ok && response.status !== 401) throw new Error("disconnect_failed");
    }
    await clearPairing(); return status();
  }
  if (message.type === "NOTI_OPEN_PAIRING" && fromSite) {
    try { await chrome.action.openPopup(); } catch { await chrome.tabs.create({ url: chrome.runtime.getURL("popup.html") }); }
    return { ok: true };
  }
  if (message.type === "NOTI_PRESENCE" && fromSite) { await updatePresence(); return status(); }
  if (message.type === "NOTI_SEND_QUESTIONS" && fromSite) {
    const url = new URL(sender.url);
    if (!/^\/banco-de-questoes\/explorar\/?$/.test(url.pathname) || !pairing || connection !== "connected") throw new Error("not_connected");
    const batch = message.batch;
    if (!Array.isArray(batch?.questions) || !batch.questions.length || batch.questions.length > 100 || JSON.stringify(batch).length > 1_000_000) throw new Error("invalid_questions");
    source = { tabId: sender.tab.id, batchId: batch.batchId, questions: batch.questions }; await chrome.storage.local.set({ source });
    return await client.publish(batch);
  }
  throw new Error("unsupported_message");
}
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  handle(message, sender).then(sendResponse, error => sendResponse({ error: error.message || "connection_failed" })); return true;
});
chrome.alarms.onAlarm.addListener(() => { void ready.then(() => { if (pairing && !client) connect(); else client?.connect(); }); });
chrome.tabs.onActivated.addListener(() => { void ready.then(updatePresence); });
chrome.tabs.onUpdated.addListener((_id, change) => { if (change.url || change.status === "complete") void ready.then(updatePresence); });
chrome.tabs.onRemoved.addListener(() => { void ready.then(updatePresence); });
chrome.windows.onFocusChanged.addListener(() => { void ready.then(updatePresence); });
