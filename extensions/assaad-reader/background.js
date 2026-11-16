import { RelayClient, SERVER } from "./transport.js";
import { runAssaadOperation } from "./platform-api.js";
let client, pairing, source;
let platformOperations = Promise.resolve(), platformNextAt = 0, platformNextAnswerAt = 0, platformCooldownUntil = 0;
let probeInFlight = false, lastProbeUrl = "", lastProbeAt = 0;
let availability = { ready: false, accountRef: null };
let commands = {}, connection = "disconnected", session = {}, answering = Promise.resolve();
const origin = "https://app.plataformaassaad.com.br";
const status = () => ({ paired: !!pairing, connection, peerOnline: !!session.peerOnline, presence: session.presence });
async function restore() {
  await chrome.storage.local.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" });
  ({ pairing, source, commands = {}, platformNextAt = 0, platformNextAnswerAt = 0, platformCooldownUntil = 0 } = await chrome.storage.local.get(["pairing", "source", "commands", "platformNextAt", "platformNextAnswerAt", "platformCooldownUntil"]));
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
      if (state === "connected" && !wasConnected) { void updatePresence(); void probeSession(); }
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
  client.send("presence", { active, path, ...availability });
}
async function platformTab(create = false) {
  const tabs = await chrome.tabs.query({ url: origin + "/*" });
  let tab = tabs.find(t => t.id === source?.tabId) || tabs[0];
  if (!tab && create) tab = await chrome.tabs.create({ url: origin + "/", active: false });
  if (!tab) return null;
  for (let i = 0; tab.status === "loading" && i < 60; i++) { await new Promise(r => setTimeout(r, 250)); tab = await chrome.tabs.get(tab.id); }
  return tab;
}
async function operation(tab, input) {
  const perform = async () => {
    if (platformCooldownUntil > Date.now()) return input.operation === "answer" ? {
      commandId: input.command.commandId, status: "blocked", reason: "rate_limited", correct: null, correctAlternativeId: null,
      feedback: "A Assaad pediu uma pausa. Retome após " + new Date(platformCooldownUntil).toLocaleTimeString() + "."
    } : { ok: false, error: "rate_limited" };
    const remaining = Math.max(platformNextAt, input.operation === "answer" ? platformNextAnswerAt : 0) - Date.now();
    if (remaining > 0) await new Promise(resolve => setTimeout(resolve, Math.min(remaining, 60000)));
    platformNextAt = Date.now() + 5000;
    if (input.operation === "answer") platformNextAnswerAt = Date.now() + 10000;
    await chrome.storage.local.set({ platformNextAt, platformNextAnswerAt });
    const results = await chrome.scripting.executeScript({ target: { tabId: tab.id }, world: "MAIN", func: runAssaadOperation, args: [input] });
    platformNextAt = Date.now() + 5000;
    if (input.operation === "answer") platformNextAnswerAt = Date.now() + 10000;
    await chrome.storage.local.set({ platformNextAt, platformNextAnswerAt });
    const value = results.find(r => r.frameId === 0)?.result;
    if (!value) throw new Error("platform_unavailable");
    if (value.retryAfterMs) { platformCooldownUntil = Date.now() + Math.min(86400000, Math.max(60000, value.retryAfterMs)); await chrome.storage.local.set({ platformCooldownUntil }); }
    // Cooldown is browser-local metadata; it is not relayed as an Assaad request/header.
    delete value.retryAfterMs;
    return value;
  };
  const next = platformOperations.catch(() => {}).then(perform); platformOperations = next; return next;
}
async function probeSession() {
  if (!pairing || !chrome.scripting || probeInFlight) return;
  probeInFlight = true;
  try {
    const tab = await platformTab();
    if (tab && tab.url === lastProbeUrl && lastProbeAt + 30000 > Date.now()) return;
    lastProbeUrl = tab?.url || ""; lastProbeAt = Date.now();
    const value = tab && await operation(tab, { operation: "session" });
    availability = { ready: value?.ok === true, accountRef: value?.accountRef || null };
  } catch { availability = { ready: false, accountRef: null }; }
  finally { probeInFlight = false; }
  await updatePresence();
}
async function answer(command) {
  const old = commands[command.commandId];
  if (old) { client?.send("answer-result", old.executing ? uncertain(command.commandId) : old); return; }
  let result;
  const batch = command.batch || (source?.batchId === command.batchId ? source : null);
  const expected = batch?.questions.find(q => q.id === command.questionId);
  if (!expected) result = failed(command.commandId, "Envie o conjunto salvo novamente ao navegador.");
  else {
    commands[command.commandId] = { executing: true }; await chrome.storage.local.set({ commands });
    try {
      if (batch.transport === "api-v1") {
        const tab = await platformTab(true);
        result = await operation(tab, { operation: "answer", batch, command: { commandId: command.commandId, batchId: command.batchId, questionId: command.questionId, alternativeId: command.alternativeId } });
      } else result = await chrome.tabs.sendMessage(source?.tabId, { type: "NOTI_ANSWER", command, expected });
      if (!result || result.commandId !== command.commandId) result = uncertain(command.commandId);
    } catch { result = uncertain(command.commandId); }
  }
  if (result.reason === "login_required" || result.reason === "account_mismatch") { availability.ready = false; await updatePresence(); }
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
    let batch = message.batch;
    if (!Array.isArray(batch?.questions) || !batch.questions.length || batch.questions.length > 100 || JSON.stringify(batch).length > 1_000_000) throw new Error("invalid_questions");
    const imported = await operation(sender.tab, { operation: "import", batch });
    if (!imported.ok) throw new Error(imported.error);
    batch = imported.batch;
    source = { ...batch, tabId: sender.tab.id }; await chrome.storage.local.set({ source });
    availability = { ready: true, accountRef: batch.accountRef }; await updatePresence();
    return await client.publish(batch);
  }
  throw new Error("unsupported_message");
}
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  handle(message, sender).then(sendResponse, error => sendResponse({ error: error.message || "connection_failed" })); return true;
});
chrome.alarms.onAlarm.addListener(() => { void ready.then(() => { if (pairing && !client) connect(); else client?.connect(); }); });
chrome.tabs.onActivated.addListener(() => { void ready.then(updatePresence); });
chrome.tabs.onUpdated.addListener((_id, change, tab) => { if (change.url || change.status === "complete") void ready.then(updatePresence); if ((change.url || change.status === "complete") && tab?.url?.startsWith(origin + "/")) void ready.then(probeSession); });
chrome.tabs.onRemoved.addListener(() => { void ready.then(updatePresence); });
chrome.windows.onFocusChanged.addListener(() => { void ready.then(updatePresence); });
