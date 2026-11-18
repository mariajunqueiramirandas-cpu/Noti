import { readQuestions } from "./reader.js";
import { answerOnPage } from "./answer.js";
const send = message => chrome.runtime.sendMessage(message);
let state = { paired: false, connection: "disconnected" }, mount, path, busy = false, answering = false, notice = "", noticeCode = "";
function refresh() {
  const currentPath = location.pathname, isQuestions = /^\/banco-de-questoes\/explorar\/?$/.test(currentPath);
  if (currentPath !== "/" && !isQuestions) { mount?.remove(); mount = null; path = currentPath; return; }
  const main = document.querySelector("main"); if (!main) return;
  const search = isQuestions && Array.from(main.querySelectorAll("button")).find(button => button.getAttribute("aria-label") === "Buscar questões" || button.textContent.trim() === "Buscar questões");
  if (mount?.isConnected && path === currentPath) {
    if (search?.parentElement && mount.parentElement !== search.parentElement) search.parentElement.append(mount);
    return;
  }
  mount?.remove();
  path = currentPath; mount = document.createElement("div"); mount.id = "noti-assaad-connection"; mount.dataset.questions = String(isQuestions);
  const button = document.createElement("button"); button.type = "button";
  button.addEventListener("click", async () => {
    if (busy) return;
    if (!state.paired || !isQuestions) { await send({ type: "NOTI_OPEN_PAIRING" }); return; }
    busy = true; noticeCode = ""; notice = "Conectando e lendo questões…"; render();
    try {
      if (state.connection === "replaced") {
        const reconnected = await send({ type: "NOTI_RECONNECT" });
        if (reconnected.error) throw Object.assign(new Error(reconnected.error), { code: reconnected.error });
        state = reconnected; notice = "Noti reconectado. Clique para enviar as questões."; return;
      }
      const snapshot = readQuestions();
      if (!snapshot.ok || !snapshot.questions.length) throw new Error("Nenhuma questão carregada nesta página.");
      if (snapshot.skipped) throw new Error("Algumas questões têm um formato não reconhecido. Atualize a extensão antes de enviar.");
      const batch = { batchId: crypto.randomUUID(), source: snapshot.source, capturedAt: snapshot.capturedAt, questions: snapshot.questions };
      const result = await send({ type: "NOTI_SEND_QUESTIONS", batch });
      if (result.error) throw Object.assign(new Error(result.error), { code: result.error });
      notice = `${result.count} questões enviadas ao Noti`;
    } catch (error) {
      noticeCode = error.code || "";
      notice = ({ not_connected: "O servidor está reconectando. Tente enviar novamente.", not_paired: "Conecte novamente os dispositivos na extensão.", session_expired: "A conexão expirou. Gere outro código no Noti.", connection_replaced: "Outra instância abriu esta conexão. Clique para reconectar aqui.", login_required: "Entre na Assaad e tente novamente.", invalid_session: "O formato da sessão Assaad mudou. Atualize a extensão.", invalid_response: "A Assaad retornou um formato inesperado. Atualize a extensão.", question_not_found: "Atualize a busca da Assaad antes de enviar.", question_changed: "A questão mudou. Atualize a página antes de enviar.", rate_limited: "A Assaad pediu uma pausa. Aguarde antes de tentar novamente.", library_full_or_conflict: "A biblioteca está cheia. Remova um conjunto salvo no Noti.", ack_timeout: "A confirmação de recebimento não chegou. Confira o Noti antes de reenviar.", timeout: "A Assaad demorou para responder. Tente novamente." })[noticeCode] || (noticeCode ? "Não foi possível enviar. Confira a conexão." : error.message || "Não foi possível enviar.");
    }
    finally { busy = false; render(); }
  });
  const text = document.createElement("span"); text.setAttribute("role", "status"); text.setAttribute("aria-live", "polite"); mount.append(button, text);
  if (search?.parentElement) search.parentElement.append(mount); else main.prepend(mount);
  render();
}
function render() {
  if (!mount) return; const button = mount.querySelector("button");
  button.textContent = !state.paired ? "Conectar ao Noti" : state.connection === "replaced" ? "Reconectar ao Noti" : mount.dataset.questions === "true" ? "Enviar questões ao Noti" : "Noti conectado";
  button.disabled = busy;
  mount.querySelector("span").textContent = notice || (state.paired ? state.connection !== "connected" ? "Reconectando…" : state.peerOnline ? "Canvas conectado" : "O Noti receberá quando abrir" : "Resolva as questões no seu canvas");
}
async function update() {
  refresh();
  try {
    const value = await send({ type: "NOTI_PRESENCE" });
    if (!value.error) {
      state = value;
      if (!busy && state.connection === "connected" && noticeCode === "not_connected") { notice = ""; noticeCode = ""; }
      render();
    }
  } catch { /* Refreshing the page restores a reloaded extension. */ }
}
chrome.runtime.onMessage.addListener((message, _sender, respond) => {
  if (message.type !== "NOTI_ANSWER") return;
  if (answering) { respond({ commandId: message.command.commandId, status: "failed", correct: null, correctAlternativeId: null, feedback: "Outra resposta está sendo confirmada. Aguarde." }); return; }
  answering = true;
  answerOnPage(message.command, message.expected, readQuestions, document).then(respond, () => respond({ commandId: message.command.commandId, status: "unknown", correct: null, correctAlternativeId: null, feedback: "Confira a confirmação no navegador." })).finally(() => { answering = false; }); return true;
});
let scheduled = false;
new MutationObserver(records => {
  const relevant = location.pathname === "/" || /^\/banco-de-questoes\/explorar\/?$/.test(location.pathname);
  if (scheduled || records.every(record => mount?.contains(record.target)) || (!relevant && path === location.pathname)) return;
  scheduled = true; setTimeout(() => { scheduled = false; const changed = path !== location.pathname || !mount?.isConnected; refresh(); if (changed) void update(); }, 250);
}).observe(document.body, { childList: true, subtree: true });
document.addEventListener("visibilitychange", () => { void update(); });
window.addEventListener("popstate", () => { refresh(); void update(); });
// Status only: no automatic reading of questions or page-control actions.
setInterval(() => { if (!document.hidden) void update(); }, 20_000);
refresh(); void update();
