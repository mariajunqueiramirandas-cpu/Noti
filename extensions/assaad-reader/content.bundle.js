// ../assaad-reader/reader.js
function readQuestions() {
  if (location.origin !== "https://app.plataformaassaad.com.br" || !/^\/banco-de-questoes\/explorar\/?$/.test(location.pathname)) {
    return { ok: false, code: "wrong-page" };
  }
  const styles = new Map;
  const visibility = new Map;
  const blockTags = new Set(["P", "DIV", "SECTION", "BLOCKQUOTE", "LI", "TR", "H1", "H2", "H3", "H4"]);
  const skippedTags = new Set(["SCRIPT", "STYLE", "TEMPLATE", "NOSCRIPT", "SVG", "BUTTON", "INPUT", "TEXTAREA", "SELECT"]);
  const clean = (text) => text.replace(/\u00a0/g, " ").replace(/[\t \f\v]+/g, " ").replace(/ *\n */g, `
`).replace(/\n{3,}/g, `

`).trim();
  function styleOf(element) {
    if (!styles.has(element))
      styles.set(element, getComputedStyle(element));
    return styles.get(element);
  }
  function visible(element) {
    if (!element)
      return true;
    if (visibility.has(element))
      return visibility.get(element);
    const style = styleOf(element);
    const value = !element.hidden && element.getAttribute("aria-hidden") !== "true" && !element.classList.contains("sr-only") && style.display !== "none" && style.visibility !== "hidden" && style.visibility !== "collapse" && style.contentVisibility !== "hidden" && style.opacity !== "0" && visible(element.parentElement);
    visibility.set(element, value);
    return value;
  }
  function imageUrl(element) {
    try {
      const raw = element.currentSrc || element.getAttribute("src");
      if (!raw)
        return null;
      const url = new URL(raw, location.href);
      return ["http:", "https:"].includes(url.protocol) ? url.href : null;
    } catch {
      return null;
    }
  }
  function content(root) {
    const images = [];
    function walk(node) {
      if (node.nodeType === 3)
        return node.nodeValue.replace(/\s+/g, " ");
      if (node.nodeType !== 1 || !visible(node) || skippedTags.has(node.tagName))
        return "";
      if (node.tagName === "BR")
        return `
`;
      if (node.tagName === "IMG") {
        const alt = clean(node.getAttribute("alt") || "");
        images.push({ alt, url: imageUrl(node) });
        return ` [Imagem${alt ? `: ${alt}` : ""}] `;
      }
      if (node.classList.contains("katex")) {
        const tex = node.querySelector('annotation[encoding="application/x-tex"]');
        if (tex)
          return ` \\(${tex.textContent.trim()}\\) `;
      }
      const text = Array.from(node.childNodes, walk).join("");
      if (blockTags.has(node.tagName))
        return `
${text}
`;
      if (["TD", "TH"].includes(node.tagName))
        return `${text} | `;
      return text;
    }
    return { text: root ? clean(walk(root)) : "", images };
  }
  const groups = Array.from(document.querySelectorAll('[role="radiogroup"][data-slot="radio-group"]')).filter(visible);
  const questions = [];
  let skipped = 0;
  for (const group of groups) {
    const card = group.closest('[data-slot="card"]');
    const promptElement = group.previousElementSibling;
    const prose = promptElement?.matches(".prose") ? promptElement : promptElement?.querySelector(".prose");
    const radios = Array.from(group.querySelectorAll('[role="radio"]')).filter((radio) => radio.closest('[role="radiogroup"]') === group && visible(radio));
    if (!card || !group.id || radios.some((radio) => !radio.id) || group.parentElement?.getAttribute("data-slot") !== "card-content" || !prose || promptElement.querySelector('[role="radiogroup"], button, input') || radios.length < 2) {
      skipped++;
      continue;
    }
    const prompt = content(prose);
    const alternatives = radios.map((radio, index) => {
      const labels = Array.from(group.querySelectorAll("label[for]")).filter((label) => label.htmlFor === radio.id && visible(label));
      const label = labels.find((candidate) => !labels.some((other) => candidate !== other && candidate.contains(other)));
      const body = content(label);
      const letter = clean(radio.parentElement?.querySelector("span")?.innerText || "");
      return {
        id: radio.id,
        label: /^[A-Z]$/.test(letter) ? letter : String.fromCharCode(65 + index),
        text: body.text,
        images: body.images
      };
    });
    if (!prompt.text && !prompt.images.length || alternatives.some((option) => !option.text && !option.images.length)) {
      skipped++;
      continue;
    }
    const metadata = promptElement.previousElementSibling;
    const badges = Array.from(metadata?.querySelectorAll('[data-slot="badge"]') || []).map((badge) => content(badge).text).filter(Boolean);
    const difficulty = badges.find((text) => /^Dificuldade:/i.test(text));
    questions.push({
      id: group.id || null,
      source: content(card.querySelector('[data-slot="card-header"] > p')).text,
      tags: badges.filter((text) => !/^Dificuldade:/i.test(text)),
      difficulty: difficulty?.replace(/^Dificuldade:\s*/i, "") || null,
      prompt: prompt.text,
      images: prompt.images,
      alternatives
    });
  }
  const pageUrl = new URL(location.origin + location.pathname);
  const query = new URLSearchParams(location.search);
  for (const key of ["category_id", "subcategory_id", "institution_id", "tag_ids", "page", "page_size", "search", "personal_filters", "difficulties", "years", "folder_id"]) {
    for (const value of query.getAll(key))
      pageUrl.searchParams.append(key, value);
  }
  return {
    ok: true,
    schemaVersion: 1,
    capturedAt: new Date().toISOString(),
    source: {
      url: pageUrl.href,
      page: content(document.querySelector('[aria-label="pagination"] [aria-current="page"]')).text || null
    },
    skipped,
    questions
  };
}

// ../assaad-reader/answer.js
async function answerOnPage(command, expected, read, document2, wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms))) {
  const result = (status, feedback, correct = null, correctAlternativeId = null) => ({ commandId: command.commandId, status, correct, correctAlternativeId, feedback });
  const snapshot = read();
  const current = snapshot.ok && snapshot.questions.find((q) => q.id === command.questionId);
  if (!current || current.prompt !== expected.prompt || !current.alternatives.some((a) => a.id === command.alternativeId && expected.alternatives.some((e) => e.id === a.id && e.text === a.text)))
    return result("failed", "A questão mudou ou saiu desta página. Envie as questões novamente.");
  const group = document2.getElementById(command.questionId), radio = document2.getElementById(command.alternativeId);
  const card = group?.closest('[data-slot="card"]');
  const confirm = card?.querySelector('button[aria-label="Confirmar resposta"]');
  if (!group || !radio || !group.contains(radio) || !confirm || radio.disabled || group.getAttribute("data-disabled") !== null)
    return result("failed", "A questão já foi respondida ou não está disponível para confirmação.");
  radio.click();
  for (let i = 0;i < 15 && confirm.disabled; i++)
    await wait(100);
  if (confirm.disabled || radio.getAttribute("aria-checked") !== "true")
    return result("failed", "A plataforma não aceitou a seleção. Confira a questão no navegador.");
  confirm.click();
  for (let i = 0;i < 100; i++) {
    await wait(100);
    const feedback = card.querySelector('[aria-live="polite"]')?.textContent?.trim() || "";
    if (/^Resposta (correta|incorreta)\./i.test(feedback)) {
      const correct = /^Resposta correta\./i.test(feedback);
      const right = Array.from(group.querySelectorAll("label[for]")).find((label) => Array.from(label.children).some((child) => child.classList.contains("text-green-500") && child.innerText?.trim() === "Resposta correta!"));
      return result("confirmed", feedback, correct, right?.htmlFor || (correct ? command.alternativeId : null));
    }
  }
  return result("unknown", "A confirmação não retornou a tempo. Confira a plataforma; a resposta não será reenviada automaticamente.");
}

// ../assaad-reader/content.js
var send = (message) => chrome.runtime.sendMessage(message);
var state = { paired: false, connection: "disconnected" };
var mount;
var path;
var busy = false;
var answering = false;
var notice = "";
var noticeCode = "";
function refresh() {
  const currentPath = location.pathname, isQuestions = /^\/banco-de-questoes\/explorar\/?$/.test(currentPath);
  if (currentPath !== "/" && !isQuestions) {
    mount?.remove();
    mount = null;
    path = currentPath;
    return;
  }
  const main = document.querySelector("main");
  if (!main)
    return;
  const search = isQuestions && Array.from(main.querySelectorAll("button")).find((button) => button.getAttribute("aria-label") === "Buscar questões" || button.textContent.trim() === "Buscar questões");
  if (mount?.isConnected && path === currentPath) {
    if (search?.parentElement && mount.parentElement !== search.parentElement)
      search.parentElement.append(mount);
    return;
  }
  mount?.remove();
  path = currentPath;
  mount = document.createElement("div");
  mount.id = "noti-assaad-connection";
  mount.dataset.questions = String(isQuestions);
  const button = document.createElement("button");
  button.type = "button";
  button.addEventListener("click", async () => {
    if (busy)
      return;
    if (!state.paired || !isQuestions) {
      await send({ type: "NOTI_OPEN_PAIRING" });
      return;
    }
    busy = true;
    noticeCode = "";
    notice = "Conectando e lendo questões…";
    render();
    try {
      if (state.connection === "replaced") {
        const reconnected = await send({ type: "NOTI_RECONNECT" });
        if (reconnected.error)
          throw Object.assign(new Error(reconnected.error), { code: reconnected.error });
        state = reconnected;
        notice = "Noti reconectado. Clique para enviar as questões.";
        return;
      }
      const snapshot = readQuestions();
      if (!snapshot.ok || !snapshot.questions.length)
        throw new Error("Nenhuma questão carregada nesta página.");
      if (snapshot.skipped)
        throw new Error("Algumas questões têm um formato não reconhecido. Atualize a extensão antes de enviar.");
      const batch = { batchId: crypto.randomUUID(), source: snapshot.source, capturedAt: snapshot.capturedAt, questions: snapshot.questions };
      const result = await send({ type: "NOTI_SEND_QUESTIONS", batch });
      if (result.error)
        throw Object.assign(new Error(result.error), { code: result.error });
      notice = `${result.count} questões enviadas ao Noti`;
    } catch (error) {
      noticeCode = error.code || "";
      notice = { not_connected: "O servidor está reconectando. Tente enviar novamente.", not_paired: "Conecte novamente os dispositivos na extensão.", session_expired: "A conexão expirou. Gere outro código no Noti.", connection_replaced: "Outra instância abriu esta conexão. Clique para reconectar aqui.", login_required: "Entre na Assaad e tente novamente.", invalid_session: "O formato da sessão Assaad mudou. Atualize a extensão.", invalid_response: "A Assaad retornou um formato inesperado. Atualize a extensão.", question_not_found: "Atualize a busca da Assaad antes de enviar.", question_changed: "A questão mudou. Atualize a página antes de enviar.", rate_limited: "A Assaad pediu uma pausa. Aguarde antes de tentar novamente.", library_full_or_conflict: "A biblioteca está cheia. Remova um conjunto salvo no Noti.", ack_timeout: "A confirmação de recebimento não chegou. Confira o Noti antes de reenviar.", timeout: "A Assaad demorou para responder. Tente novamente." }[noticeCode] || (noticeCode ? "Não foi possível enviar. Confira a conexão." : error.message || "Não foi possível enviar.");
    } finally {
      busy = false;
      render();
    }
  });
  const text = document.createElement("span");
  text.setAttribute("role", "status");
  text.setAttribute("aria-live", "polite");
  mount.append(button, text);
  if (search?.parentElement)
    search.parentElement.append(mount);
  else
    main.prepend(mount);
  render();
}
function render() {
  if (!mount)
    return;
  const button = mount.querySelector("button");
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
      if (!busy && state.connection === "connected" && noticeCode === "not_connected") {
        notice = "";
        noticeCode = "";
      }
      render();
    }
  } catch {}
}
chrome.runtime.onMessage.addListener((message, _sender, respond) => {
  if (message.type !== "NOTI_ANSWER")
    return;
  if (answering) {
    respond({ commandId: message.command.commandId, status: "failed", correct: null, correctAlternativeId: null, feedback: "Outra resposta está sendo confirmada. Aguarde." });
    return;
  }
  answering = true;
  answerOnPage(message.command, message.expected, readQuestions, document).then(respond, () => respond({ commandId: message.command.commandId, status: "unknown", correct: null, correctAlternativeId: null, feedback: "Confira a confirmação no navegador." })).finally(() => {
    answering = false;
  });
  return true;
});
var scheduled = false;
new MutationObserver((records) => {
  const relevant = location.pathname === "/" || /^\/banco-de-questoes\/explorar\/?$/.test(location.pathname);
  if (scheduled || records.every((record) => mount?.contains(record.target)) || !relevant && path === location.pathname)
    return;
  scheduled = true;
  setTimeout(() => {
    scheduled = false;
    const changed = path !== location.pathname || !mount?.isConnected;
    refresh();
    if (changed)
      update();
  }, 250);
}).observe(document.body, { childList: true, subtree: true });
document.addEventListener("visibilitychange", () => {
  update();
});
window.addEventListener("popstate", () => {
  refresh();
  update();
});
setInterval(() => {
  if (!document.hidden)
    update();
}, 20000);
refresh();
update();
