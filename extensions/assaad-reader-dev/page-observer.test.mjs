import test from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import { readFileSync } from "node:fs";
const code = readFileSync(new URL("../assaad-reader/page-observer.js", import.meta.url), "utf8");
const session = { id: "test-user", access_token: "PAGE_TOKEN", expires_in: 3600 };
const question = { id: "00000000-0000-4000-8000-000000000001", text: "Q", alternatives: [] };

/** A page with the observer installed first, as at document start. */
function page() {
  const sent = [];
  const responses = {
    "https://app.plataformaassaad.com.br/api/auth/session": { session },
    "https://api.plataformaassaad.com.br/v1/question?page=1&perPage=20": { data: [question] },
  };
  async function fetch(input, init = {}) {
    const url = new URL(typeof input === "string" ? input : input.url, "https://app.plataformaassaad.com.br").href;
    sent.push({ url, method: init.method || "GET" });
    const body = responses[url];
    return new Response(JSON.stringify(body ?? {}), { status: body ? 200 : 404, headers: { "Content-Type": "application/json" } });
  }
  class XMLHttpRequest extends EventTarget {
    open(method, url) { this.method = method; this.url = url; }
    send() {
      sent.push({ url: this.url, method: this.method });
      this.status = 200; this.responseType = ""; this.responseURL = this.url;
      this.responseText = JSON.stringify(responses[this.url] ?? {});
      this.dispatchEvent(new Event("load"));
    }
  }
  const context = vm.createContext({ fetch, XMLHttpRequest, URL, Response, Event, EventTarget, JSON, Date, Array, Object, String });
  context.globalThis = context;
  vm.runInContext(code, context);
  vm.runInContext(code, context); // Installing twice is harmless.
  return { context, sent };
}
const settle = () => new Promise(resolve => setTimeout(resolve, 10));

test("remembers the website's own session and searches without sending anything itself", async () => {
  const { context, sent } = page();
  const response = await context.fetch("/api/auth/session", { credentials: "include" });
  assert.equal((await response.json()).session.access_token, "PAGE_TOKEN", "the website still gets its own response");
  const xhr = new context.XMLHttpRequest();
  xhr.open("GET", "https://api.plataformaassaad.com.br/v1/question?page=1&perPage=20"); xhr.send();
  await settle();
  const observed = context.__notiAssaad;
  assert.equal(observed.session().access_token, "PAGE_TOKEN");
  assert.equal(observed.question(question.id).text, "Q");
  assert.equal(observed.apiReachable(), true);
  assert.equal(sent.length, 2, "only the website's two requests went out");
  observed.forgetSession(); assert.equal(observed.session(), null);
});

test("failed or unrelated responses are not remembered", async () => {
  const { context } = page();
  await context.fetch("https://api.plataformaassaad.com.br/v1/question?page=9"); // 404
  await context.fetch("https://example.com/api/auth/session");
  await settle();
  assert.equal(context.__notiAssaad.session(), null);
  assert.equal(context.__notiAssaad.question(question.id), null);
  assert.equal(context.__notiAssaad.apiReachable(), false);
});
