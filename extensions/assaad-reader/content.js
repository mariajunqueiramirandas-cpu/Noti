import { readQuestions } from "./reader.js";
import { answerOnPage } from "./answer.js";
const send = message => chrome.runtime.sendMessage(message);
let state = { paired: false, connection: "disconnected" }, mount, path, busy = false, answering = false, notice = "";
function refresh() {
  const currentPath = location.pathname, isQuestions = /^\/banco-de-questoes\/explorar\/?$/.test(currentPath);
  if (currentPath !== "/" && !isQuestions) { mount?.remove(); mount = null; path = currentPath; return; }
  if (mount?.isConnected && path === currentPath) return;
  mount?.remove(); const main = document.querySelector("main"); if (!main) return;
  path = currentPath; mount = document.createElement("div"); mount.id = "noti-assaad-connection"; mount.dataset.questions = String(isQuestions);
  const button = document.createElement("button"); button.type = "button";
  button.addEventListener("click", async () => {
    if (busy) return;
    if (!state.paired || !isQuestions) { await send({ type: "NOTI_OPEN_PAIRING" }); return; }
    busy = true; notice = "Lendo questões…"; render();
    try {
      const snapshot = readQuestions();
      if (!snapshot.ok || !snapshot.questions.length) throw new Error("Nenhuma questão carregada nesta página.");
      if (snapshot.skipped) throw new Error("Algumas questões têm um formato não reconhecido. Atualize a extensão antes de enviar.");
      const batch = { batchId: crypto.randomUUID(), source: snapshot.source, capturedAt: snapshot.capturedAt, questions: snapshot.questions };
      const result = await send({ type: "NOTI_SEND_QUESTIONS", batch });
      if (result.error) throw new Error(result.error === "not_connected" ? "Reconecte o Noti antes de enviar." : "Não foi possível enviar. Confira a conexão.");
      notice = `${result.count} questões enviadas ao Noti`;
    } catch (error) { notice = error.message || "Não foi possível enviar."; }
    finally { busy = false; render(); }
  });
  const text = document.createElement("span"); text.setAttribute("role", "status"); text.setAttribute("aria-live", "polite"); mount.append(button, text);
  const search = isQuestions && main.querySelector('button[aria-label="Buscar questões"]');
  if (search?.parentElement) search.parentElement.append(mount); else main.prepend(mount);
  render();
}
function render() {
  if (!mount) return; const button = mount.querySelector("button");
  button.textContent = !state.paired ? "Conectar ao Noti" : mount.dataset.questions === "true" ? "Enviar questões ao Noti" : "Noti conectado";
  button.disabled = busy || (state.paired && mount.dataset.questions === "true" && state.connection !== "connected");
  mount.querySelector("span").textContent = notice || (state.paired ? state.connection !== "connected" ? "Reconectando…" : state.peerOnline ? "Canvas conectado" : "O Noti receberá quando abrir" : "Resolva as questões no seu canvas");
}
async function update() {
  try { const value = await send({ type: "NOTI_PRESENCE" }); if (!value.error) { state = value; render(); } } catch { /* Refreshing the page restores a reloaded extension. */ }
}
chrome.runtime.onMessage.addListener((message, _sender, respond) => {
  if (message.type !== "NOTI_ANSWER") return;
  if (answering) { respond({ commandId: message.command.commandId, status: "failed", correct: null, correctAlternativeId: null, feedback: "Outra resposta está sendo confirmada. Aguarde." }); return; }
  answering = true;
  answerOnPage(message.command, message.expected, readQuestions, document).then(respond, () => respond({ commandId: message.command.commandId, status: "unknown", correct: null, correctAlternativeId: null, feedback: "Confira a confirmação no navegador." })).finally(() => { answering = false; }); return true;
});
let scheduled = false;
new MutationObserver(() => {
  const relevant = location.pathname === "/" || /^\/banco-de-questoes\/explorar\/?$/.test(location.pathname);
  if (scheduled || (path === location.pathname && (!relevant || mount?.isConnected))) return;
  scheduled = true; setTimeout(() => { scheduled = false; refresh(); void update(); }, 250);
}).observe(document.body, { childList: true, subtree: true });
document.addEventListener("visibilitychange", () => { void update(); });
window.addEventListener("popstate", () => { refresh(); void update(); });
// Status only: no automatic reading of questions or page-control actions.
setInterval(() => { if (!document.hidden) void update(); }, 20_000);
refresh(); void update();
