/** Runs only in Assaad's MAIN world. No tokens, cookies, or raw API responses leave it. */
export async function runAssaadOperation(input) {
  const origin = "https://app.plataformaassaad.com.br";
  const api = "https://api.plataformaassaad.com.br/v1";
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  let writing = false, lastRequestAt = 0, stage = "session";
  // What the website itself already loaded on this page (page-observer.js), so Noti
  // doesn't ask Assaad again for a session or a search the site just made.
  const observed = globalThis.__notiAssaad || null;
  // Fixed, visible pacing for fair use; no timing randomization or detection bypass.
  async function politeFetch(url, options) {
    const remaining = 2000 - (Date.now() - lastRequestAt);
    if (remaining > 0) await new Promise(resolve => setTimeout(resolve, remaining));
    lastRequestAt = Date.now();
    return fetch(url, options);
  }
  const result = (status, feedback, correct = null, correctAlternativeId = null) => ({ commandId: input.command?.commandId, status, feedback, correct, correctAlternativeId });
  const stop = code => { throw new Error(code); };
  async function json(response) {
    if (response.status === 429) {
      const raw = response.headers.get("Retry-After");
      const delay = raw && Number.isFinite(Number(raw)) ? Number(raw) * 1000 : raw ? Date.parse(raw) - Date.now() : 60000;
      const error = new Error("rate_limited"); error.retryAfterMs = Math.min(86400000, Math.max(60000, Number.isFinite(delay) ? delay : 60000)); throw error;
    }
    if (!response.ok) stop(response.status === 401 ? "login_required" : response.status === 429 ? "rate_limited" : response.status === 403 ? "access_denied" : "http_" + response.status);
    const reader = response.body.getReader(); const chunks = []; let size = 0;
    while (true) { const next = await reader.read(); if (next.done) break; size += next.value.length; if (size > 2_097_152) { await reader.cancel(); stop("response_too_large"); } chunks.push(next.value); }
    const bytes = new Uint8Array(size); let offset = 0; for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    return JSON.parse(new TextDecoder().decode(bytes));
  }
  async function digest(value) { return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))), b => b.toString(16).padStart(2, "0")).join(""); }
  function text(value, max) { if (typeof value !== "string" || value.length > max) stop("invalid_content"); return value; }
  const clean = value => value.replace(/\u00a0/g, " ").replace(/[\t \f\v]+/g, " ").replace(/ *\n */g, "\n").replace(/\n{3,}/g, "\n\n").trim();
  function content(value, image) {
    const images = [];
    function addImage(raw, alt) {
      if (!raw) return;
      const url = new URL(raw, origin);
      if (url.protocol !== "https:" || url.username || url.password || url.href.length > 4096) stop("invalid_image");
      images.push({ alt: String(alt || "").slice(0, 2000), url: url.href });
    }
    const parsed = new DOMParser().parseFromString(text(value || "", 100000), "text/html");
    function walk(node) {
      if (node.nodeType === 3) return node.nodeValue;
      if (node.nodeType !== 1 || ["SCRIPT", "STYLE", "IFRAME", "OBJECT", "TEMPLATE"].includes(node.tagName)) return "";
      if (node.tagName === "IMG") { addImage(node.getAttribute("src"), node.getAttribute("alt")); return " [Imagem] "; }
      if (node.tagName === "BR") return "\n";
      const latex = node.getAttribute("data-latex") || node.querySelector('annotation[encoding="application/x-tex"]')?.textContent;
      if (latex) return " \\(" + latex + "\\) ";
      const value = Array.from(node.childNodes, walk).join("");
      return ["P", "DIV", "LI", "TABLE", "TR", "BLOCKQUOTE"].includes(node.tagName) ? "\n" + value + "\n" : value;
    }
    const valueText = clean(walk(parsed.body)); addImage(image, "");
    return { text: valueText, images };
  }
  function normalize(q) {
    if (!q || !uuid.test(q.id) || !Array.isArray(q.alternatives) || q.alternatives.length < 2 || q.alternatives.length > 12) stop("invalid_question");
    const prompt = content(q.text, q.image);
    const alternatives = q.alternatives.map((a, i) => {
      if (!uuid.test(a.id)) stop("invalid_alternative");
      const body = content(a.text, a.image);
      return { id: a.id, label: String(a.label || String.fromCharCode(65 + i)).slice(0, 10), text: text(body.text, 20000), images: body.images };
    });
    if (new Set(alternatives.map(a => a.id)).size !== alternatives.length || prompt.images.length + alternatives.reduce((n, a) => n + a.images.length, 0) > 16) stop("invalid_question");
    return { id: q.id, prompt: text(prompt.text, 50000), images: prompt.images, alternatives,
      source: [q.institution?.name, q.category?.name, q.subcategory?.name, q.year].filter(v => v != null).join(" | ").slice(0, 2000),
      tags: (q.tag_links || []).map(link => String(link.tag?.name || "").slice(0, 200)).filter(Boolean).slice(0, 30),
      difficulty: q.difficulty == null ? null : String(q.difficulty).slice(0, 100) };
  }
  function query(source, search) {
    const url = new URL(source.url);
    if (url.origin !== origin || !/^\/banco-de-questoes\/explorar\/?$/.test(url.pathname)) stop("invalid_source");
    const params = new URLSearchParams({ page: String(Math.max(1, Math.min(10000, Number(url.searchParams.get("page") || source.page) || 1))), perPage: String(Math.max(5, Math.min(100, Number(url.searchParams.get("page_size")) || 20))), is_published: "true" });
    const keys = { category_id: "category_ids", subcategory_id: "subcategory_ids", institution_id: "institution_ids", tag_ids: "tag_ids", difficulties: "difficulties", years: "years" };
    for (const [from, to] of Object.entries(keys)) for (const raw of url.searchParams.getAll(from)) for (const value of raw.split(",").slice(0, 30)) if (/^[0-9]{1,10}$/.test(value)) params.append(to, value);
    if (input.operation === "import") {
      for (const raw of url.searchParams.getAll("personal_filters")) for (const value of raw.split(",")) if (/^[a-z_-]{1,40}$/i.test(value)) params.append("personal", value);
      const folder = url.searchParams.get("folder_id"); if (folder && /^[a-z0-9_-]{1,100}$/i.test(folder)) params.set("folder_id", folder);
    }
    if (search !== undefined) { for (const key of [...params.keys()]) if (!["is_published"].includes(key)) params.delete(key); params.set("page", "1"); params.set("perPage", "20"); params.set("search", search.slice(0, 160)); }
    else if (url.searchParams.get("search")) params.set("search", url.searchParams.get("search").slice(0, 160));
    return "/question?" + params;
  }
  const fingerprint = q => JSON.stringify({ prompt: q.prompt, images: q.images, alternatives: [...q.alternatives].sort((a, b) => a.id.localeCompare(b.id)).map(a => ({ id: a.id, text: a.text, images: a.images })) });
  try {
    if (location.origin !== origin || !["session", "connect", "import", "verify", "answer"].includes(input.operation)) stop("invalid_operation");
    const session = observed?.session() || (await json(await politeFetch("/api/auth/session", { credentials: "include", cache: "no-store", redirect: "error", signal: AbortSignal.timeout(10000) }))).session;
    // The website's cookie session is flat: {id,email,access_token,...}.
    // A nested Supabase-style user object is not the production response contract.
    if (!session?.access_token) stop("login_required");
    const accountId = session.id ?? session.user?.id;
    if (typeof accountId !== "string" || !accountId.trim() || accountId.length > 200) stop("invalid_session");
    const accountRef = await digest("assaad:" + accountId);
    if (input.operation === "session") return { ok: true, accountRef };
    async function request(path, body) {
      return json(await politeFetch(api + path, { method: body ? "PATCH" : "GET", headers: { Authorization: "Bearer " + session.access_token, ...(body ? { "Content-Type": "application/json" } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}), cache: "no-store", redirect: "error", signal: AbortSignal.timeout(30000) }));
    }
    if (input.operation === "connect") {
      // The website's own API calls on this page already prove the authenticated connection.
      if (observed?.apiReachable()) return { ok: true, accountRef, corsVerified: true };
      stage = "metadata";
      const metadata = await request("/question/metadata");
      if (!Array.isArray(metadata.tags) && !Array.isArray(metadata.category_groups)) stop("invalid_response");
      return { ok: true, accountRef, corsVerified: true };
    }
    const source = input.batch?.source;
    if (input.operation === "import") {
      stage = "questions";
      if (!input.batch || !Array.isArray(input.batch.questions) || input.batch.questions.length < 1 || input.batch.questions.length > 100) stop("invalid_batch");
      const search = query(source);
      // The page just showed these questions: the website's own search response has them.
      // Only when it can't be found (an older page, another tab) is the same search asked again.
      let rows = input.batch.questions.map(expected => observed?.question(expected.id));
      if (rows.some(raw => !raw)) {
        const response = await request(search);
        if (!Array.isArray(response.data)) stop("invalid_response");
        rows = input.batch.questions.map(expected => response.data.find(q => q.id === expected.id));
      }
      const questions = input.batch.questions.map((expected, i) => {
        const raw = rows[i];
        if (!raw) stop("question_not_found");
        const q = normalize(raw);
        if (q.alternatives.length !== expected.alternatives.length || q.alternatives.some(a => !expected.alternatives.some(e => e.id === a.id))) stop("question_changed");
        return q;
      });
      return { ok: true, batch: { ...input.batch, accountRef, transport: "api-v1", questions } };
    }
    if (input.batch?.transport !== "api-v1" || accountRef !== input.batch.accountRef) stop("account_mismatch");
    const command = input.command;
    const expected = input.batch.questions.find(q => q.id === command.questionId);
    if (!uuid.test(command.commandId) || !uuid.test(command.questionId) || !uuid.test(command.alternativeId) || command.batchId !== input.batch.batchId || !expected?.alternatives.some(a => a.id === command.alternativeId)) stop("invalid_command");
    stage = "questions";
    const search = query(source);
    if (input.operation === "verify") {
      // An explicit read-only check: look the question up again.
      let response = await request(search);
      if (!Array.isArray(response.data)) stop("invalid_response");
      let raw = response.data.find(q => q.id === command.questionId);
      if (!raw && expected.prompt.length >= 12) {
        response = await request(query(source, clean(expected.prompt).replace(/\s+/g, " ").slice(0, 100)));
        if (!Array.isArray(response.data)) stop("invalid_response");
        raw = response.data.find(q => q.id === command.questionId);
      }
      if (!raw) stop("question_not_found");
      if (fingerprint(normalize(raw)) !== fingerprint(expected)) stop("question_changed");
      return { ok: true, matched: true, accountRef };
    }
    // An answer is just the answer, as when you click an option on the website. When the
    // page has the question loaded, a changed question is still caught, without asking again.
    const seen = observed?.question(command.questionId);
    if (seen && fingerprint(normalize(seen)) !== fingerprint(expected)) stop("question_changed");
    writing = true; stage = "confirmation";
    const answer = await request("/question/answer", { question_id: command.questionId, alternative_id: command.alternativeId });
    if (answer.created?.confirmed !== true || answer.created.alternative_id !== command.alternativeId || (answer.created.question_id !== undefined && answer.created.question_id !== command.questionId) || typeof answer.correct !== "boolean") stop("confirmation_unverified");
    const right = answer.correctAnswer?.id;
    if (right && (!expected.alternatives.some(a => a.id === right) || answer.correct !== (right === command.alternativeId))) stop("confirmation_unverified");
    return result("confirmed", answer.correct ? "Resposta correta." : "Resposta incorreta.", answer.correct, right || (answer.correct ? command.alternativeId : null));
  } catch (error) {
    const code = error.message;
    if (input.operation !== "answer") return { ok: false, stage, ...(error.retryAfterMs ? { retryAfterMs: error.retryAfterMs } : {}), error: ["login_required", "rate_limited", "access_denied", "question_changed", "question_not_found", "invalid_session", "invalid_response"].includes(code) ? code : error.name === "TimeoutError" ? "timeout" : "platform_unavailable" };
    // Once a write starts, any failure is uncertain. It is never replayed automatically.
    // A refused login (401) is the one exception: Assaad turned it away before doing anything.
    if (code === "login_required") observed?.forgetSession();
    if (writing && code === "login_required") writing = false;
    return { ...(error.retryAfterMs ? { retryAfterMs: error.retryAfterMs } : {}), ...result(writing ? "unknown" : "blocked", writing ? "Confira a resposta na Assaad antes de tentar novamente." : code === "login_required" ? "Entre na Assaad no navegador para continuar." : code === "account_mismatch" ? "Abra a mesma conta Assaad usada para salvar estas questões." : code === "rate_limited" ? "A Assaad pediu uma pausa. Aguarde e retome a fila." : "A questão não pôde ser verificada. Atualize o conjunto antes de enviar."), reason: ["login_required", "account_mismatch", "rate_limited", "question_changed", "question_not_found", "access_denied"].includes(code) ? code : "platform_unavailable" };
  }
}
