/** Runs only in Assaad's MAIN world. No tokens, cookies, or raw API responses leave it. */
export async function runAssaadOperation(input) {
  const origin = "https://app.plataformaassaad.com.br";
  const api = "https://api.plataformaassaad.com.br/v1";
  const searchPageSize = 20;
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
  function searchSource(input) {
    const url = new URL("/banco-de-questoes/explorar", origin);
    const page = input.page ?? 1;
    if (!Number.isInteger(page) || page < 1 || page > 10000) stop("invalid_search");
    url.searchParams.set("page", String(page));
    url.searchParams.set("page_size", String(searchPageSize));
    const filters = input.filters ?? {};
    for (const key of ["category_id", "subcategory_id", "institution_id", "tag_ids", "difficulties", "years"]) {
      const value = filters[key];
      if (value == null || value === "") continue;
      if (!["string", "number"].includes(typeof value) || !/^[0-9]{1,10}$/.test(String(value))) stop("invalid_search");
      url.searchParams.set(key, String(value));
    }
    if (filters.search != null) {
      const value = text(filters.search, 160).trim();
      if (value) url.searchParams.set("search", value);
    }
    return { url: url.href, page: String(page) };
  }
  function filterOptions(raw) {
    const list = value => Array.isArray(value) ? value : [];
    const option = value => {
      const id = value?.id, label = value?.name ?? value?.label;
      if (["string", "number"].includes(typeof id) && /^[0-9]{1,10}$/.test(String(id)) && typeof label === "string" && label.trim()) {
        return { id: String(id), label: label.trim().slice(0, 200) };
      }
      return null;
    };
    const unique = values => [...new Map(values.filter(Boolean).map(value => [value.id, value])).values()];
    const categories = [...list(raw.lone_categories), ...list(raw.category_groups).flatMap(group => list(group?.categories))];
    return {
      categories: unique(categories.map(category => {
        const value = option(category);
        return value && { ...value, topics: unique(list(category.subcategories ?? category.sub_categories).map(option)) };
      })),
      tags: unique(list(raw.tags).map(option)),
      institutions: unique(list(raw.institutions).map(option)),
      years: unique(list(raw.years).map(value => {
        const year = String(value?.year ?? value);
        return /^[0-9]{4}$/.test(year) ? { id: year, label: year } : null;
      })),
    };
  }
  const fingerprint = q => JSON.stringify({ prompt: q.prompt, images: q.images, alternatives: [...q.alternatives].sort((a, b) => a.id.localeCompare(b.id)).map(a => ({ id: a.id, text: a.text, images: a.images })) });
  try {
    if (location.origin !== origin || !["session", "connect", "metadata", "search", "import", "verify", "answer"].includes(input.operation)) stop("invalid_operation");
    const selectedSource = input.operation === "search" ? searchSource(input) : null;
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
    if (input.operation === "connect" || input.operation === "metadata") {
      // The website's own API calls on this page already prove the authenticated connection.
      if (input.operation === "connect" && observed?.apiReachable()) return { ok: true, accountRef, corsVerified: true };
      stage = "metadata";
      const metadata = observed?.metadata?.() || await request("/question/metadata");
      if (!metadata || (!Array.isArray(metadata.tags) && !Array.isArray(metadata.category_groups))) stop("invalid_response");
      return input.operation === "connect" ? { ok: true, accountRef, corsVerified: true } : { ok: true, options: filterOptions(metadata) };
    }
    if (selectedSource) {
      stage = "questions";
      const response = await request(query(selectedSource));
      if (!Array.isArray(response?.data) || response.data.length > searchPageSize) stop("invalid_response");
      const questions = response.data.map(normalize);
      const count = response.meta?.total;
      const total = Number.isSafeInteger(count) && count >= 0 ? count : null;
      const reportedLastPage = response.meta?.lastPage;
      const lastPage = Number.isSafeInteger(reportedLastPage) && reportedLastPage >= 0
        ? reportedLastPage
        : total == null ? null : Math.ceil(total / searchPageSize);
      return {
        ok: true,
        total,
        lastPage: lastPage == null ? null : Math.min(10000, lastPage),
        batch: { batchId: crypto.randomUUID(), source: selectedSource, capturedAt: new Date().toISOString(), accountRef, transport: "api-v1", questions },
      };
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
    if (code === "login_required") observed?.forgetSession();
    if (input.operation !== "answer") return { ok: false, stage, ...(error.retryAfterMs ? { retryAfterMs: error.retryAfterMs } : {}), error: ["login_required", "rate_limited", "access_denied", "question_changed", "question_not_found", "invalid_session", "invalid_response", "invalid_search"].includes(code) ? code : error.name === "TimeoutError" ? "timeout" : "platform_unavailable" };
    // Once a write starts, any failure is uncertain. It is never replayed automatically.
    // A refused login (401) is the one exception: Assaad turned it away before doing anything.
    if (writing && code === "login_required") writing = false;
    return { ...(error.retryAfterMs ? { retryAfterMs: error.retryAfterMs } : {}), ...result(writing ? "unknown" : "blocked", writing ? "Confira a resposta na Assaad antes de tentar novamente." : code === "login_required" ? "Entre na Assaad no navegador para continuar." : code === "account_mismatch" ? "Abra a mesma conta Assaad usada para salvar estas questões." : code === "rate_limited" ? "A Assaad pediu uma pausa. Aguarde e retome a fila." : "A questão não pôde ser verificada. Atualize o conjunto antes de enviar."), reason: ["login_required", "account_mismatch", "rate_limited", "question_changed", "question_not_found", "access_denied"].includes(code) ? code : "platform_unavailable" };
  }
}
