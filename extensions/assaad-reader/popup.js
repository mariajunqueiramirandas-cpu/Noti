const byId = id => document.getElementById(id);
const request = message => chrome.runtime.sendMessage(message);
function status(text, error = false) { byId("status").textContent = text; byId("status").dataset.error = String(error); }
function render(value) {
  byId("pair-view").hidden = value.paired; byId("connected-view").hidden = !value.paired;
  byId("peer").textContent = value.connection !== "connected" ? "Reconectando ao servidor…" : value.peerOnline ? "Noti online · canvas disponível" : "Abra o Noti para continuar. As questões enviadas ficam aguardando.";
}
const errors = { expired_or_used_code: "Código expirado ou já utilizado. Gere outro no Noti.", invalid_code: "Digite os 6 dígitos exibidos no Noti.", rate_limited: "Muitas tentativas. Aguarde um minuto.", disconnect_failed: "Não foi possível encerrar a sessão no servidor. Tente novamente." };
byId("code").addEventListener("input", event => { event.target.value = event.target.value.replace(/\D/g, "").slice(0, 6); });
byId("pair-form").addEventListener("submit", async event => {
  event.preventDefault(); const button = byId("pair"); button.disabled = true; status("Conectando dispositivos…");
  try {
    const value = await request({ type: "NOTI_PAIR", code: byId("code").value }); if (value.error) throw new Error(value.error);
    byId("code").value = ""; render(value); status("Dispositivos pareados.");
  } catch (error) { status(errors[error.message] || "Não foi possível conectar. Confira a internet e tente novamente.", true); }
  finally { button.disabled = false; }
});
byId("disconnect").addEventListener("click", async () => {
  byId("disconnect").disabled = true;
  try { const value = await request({ type: "NOTI_DISCONNECT" }); if (value.error) throw new Error(value.error); render(value); status("Sessão encerrada nos dois dispositivos."); }
  catch (error) { status(errors[error.message] || "Confira a conexão e tente novamente.", true); }
  finally { byId("disconnect").disabled = false; }
});
async function refresh() { try { const value = await request({ type: "NOTI_STATUS" }); if (!value.error) render(value); } catch { status("Reabra a extensão para conectar.", true); } }
void refresh(); setInterval(refresh, 2000);
