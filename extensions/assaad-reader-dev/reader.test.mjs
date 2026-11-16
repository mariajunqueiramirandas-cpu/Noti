import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { Window } from "happy-dom";
import { fixture, questionCard } from "./fixture.mjs";

const root = new URL("../assaad-reader/", import.meta.url);
const reader = readFileSync(new URL("reader.js", root), "utf8").replace("export function", "function");
const formatter = await import(`data:text/javascript;base64,${Buffer.from(readFileSync(new URL("format.js", root))).toString("base64")}`);
function page(html = fixture(), url = "https://app.plataformaassaad.com.br/banco-de-questoes/explorar?category_id=567&subcategory_id=719&tag_ids=422&access_token=SECRET#private") {
  const window = new Window({ url });
  window.document.write(html);
  return window;
}
function read(window) {
  // Execute as a serialized function, just like chrome.scripting: no module
  // closure, dependencies, extension helpers, or application state available.
  return JSON.parse(JSON.stringify(window.eval(`${reader}\nreadQuestions();`)));
}

test("extracts known question layout without duplicates, account text, filters, or explanations", () => {
  const window = page();
  const result = read(window);
  assert.equal(result.ok, true);
  assert.equal(result.questions.length, 1);
  assert.equal(result.source.page, "1");
  assert.equal(result.source.url, "https://app.plataformaassaad.com.br/banco-de-questoes/explorar?category_id=567&subcategory_id=719&tag_ids=422");
  const [question] = result.questions;
  assert.equal(question.id, "sample-question");
  assert.equal(question.source, "INSTITUIÇÃO EXEMPLO | História | Idade Antiga | 2026");
  assert.deepEqual(question.tags, ["Egito"]);
  assert.equal(question.difficulty, "Fácil");
  assert.deepEqual(question.alternatives.map(({ label, text }) => [label, text]), [["A", "Rio Nilo"], ["B", "Rio Amazonas"]]);
  assert.equal(question.images[0].url, "https://example.com/nilo.webp");
  assert.doesNotMatch(JSON.stringify(result), /SECRET|PRIVATE|EXPLANATION|Confirmar|Reportar|Não respondidas/);
  assert.equal(Object.hasOwn(question.alternatives[1], "checked"), false);
});

test("reads all rendered cards below the fold, but omits hidden cards and hidden option text", () => {
  const window = page(`${questionCard("one")}<div style="margin-top:10000px">${questionCard("two")}</div><div hidden>${questionCard("hidden")}</div><div style="opacity:0">${questionCard("transparent")}</div>`);
  assert.deepEqual(read(window).questions.map(({ id }) => id), ["one", "two"]);
});

test("preserves math once, paragraph breaks, safe image references, and punctuation", () => {
  const math = '<p>Área: <span class="katex"><span class="katex-mathml"><annotation encoding="application/x-tex">A=bh</annotation></span><span aria-hidden="true">DUPLICATE MATH</span></span>.</p><img src="javascript:alert(1)" alt="Desenho"><p>Fim.<br>Outra linha.</p>';
  const result = read(page(questionCard("math", math)));
  assert.match(result.questions[0].prompt, /Área: \\\(A=bh\\\)/);
  assert.doesNotMatch(result.questions[0].prompt, /DUPLICATE/);
  assert.match(result.questions[0].prompt, /Fim\.\nOutra linha\./);
  assert.equal(result.questions[0].images[1].url, null);
});

test("never mutates the page, invokes controls, reads credentials, or initiates requests", () => {
  const window = page();
  const before = window.document.documentElement.outerHTML;
  const fail = () => { throw new Error("Forbidden side effect"); };
  window.fetch = fail;
  window.XMLHttpRequest = fail;
  window.HTMLElement.prototype.click = fail;
  Object.defineProperty(window.document, "cookie", { get: fail });
  Object.defineProperty(window, "localStorage", { get: fail });
  Object.defineProperty(window, "sessionStorage", { get: fail });
  assert.equal(read(window).questions.length, 1);
  assert.equal(window.document.documentElement.outerHTML, before);
});

test("rejects foreign origins, lookalike hosts, login pages, and unrelated Assaad routes", () => {
  for (const url of ["https://example.com/banco-de-questoes/explorar", "https://app.plataformaassaad.com.br.evil.example/banco-de-questoes/explorar", "http://app.plataformaassaad.com.br/banco-de-questoes/explorar", "https://app.plataformaassaad.com.br/login", "https://app.plataformaassaad.com.br/banco-de-questoes/explorar/extra"]) {
    assert.deepEqual(read(page(fixture(), url)), { ok: false, code: "wrong-page" });
  }
  assert.equal(read(page(fixture(), "https://app.plataformaassaad.com.br/banco-de-questoes/explorar/")).ok, true);
});

test("returns useful empty/partial outcomes and fails closed on incompatible markup", () => {
  assert.equal(read(page("<p>Faça login</p>")).questions.length, 0);
  const window = page(questionCard("good") + questionCard("broken"));
  window.document.getElementById("broken").previousElementSibling.remove();
  const result = read(window);
  assert.equal(result.questions.length, 1);
  assert.equal(result.skipped, 1);
  window.document.getElementById("good-b").closest('label').remove();
  assert.equal(read(window).questions.length, 0);
});

test("exports complete plain text and JSON-safe strings including images", () => {
  const snapshot = read(page());
  const text = formatter.asText(snapshot);
  assert.match(text, /QUESTÃO 1/);
  assert.match(text, /A\) Rio Nilo/);
  assert.match(text, /B\) Rio Amazonas/);
  assert.match(text, /Imagem \(Mapa do Nilo\): https:\/\/example\.com\/nilo.webp/);
  assert.doesNotMatch(text, /SECRET/);
  assert.match(formatter.exportName(snapshot, "json"), /^assaad-pagina-1-.*\.json$/);
  assert.doesNotMatch(formatter.exportName(snapshot, "txt"), /[:/\\]/);
});

test("manifest limits persistent access to Assaad and the user-specified relay", () => {
  const manifest = JSON.parse(readFileSync(new URL("manifest.json", root), "utf8"));
  assert.equal(manifest.manifest_version, 3);
  assert.deepEqual(manifest.permissions.sort(), ["alarms", "storage"]);
  assert.deepEqual(manifest.host_permissions, ["https://app.plataformaassaad.com.br/*", "https://noti.vendua.com.br/*"]);
  assert.deepEqual(manifest.content_scripts[0].matches, ["https://app.plataformaassaad.com.br/*"]);
  for (const key of ["optional_host_permissions", "externally_connectable", "web_accessible_resources"]) assert.equal(manifest[key], undefined);
  assert.match(manifest.content_security_policy.extension_pages, /connect-src https:\/\/noti.vendua.com.br wss:\/\/noti.vendua.com.br/);
  assert.match(manifest.content_security_policy.extension_pages, /script-src 'self'/);
  assert.ok(readFileSync(new URL(manifest.action.default_popup, root), "utf8"));
});
