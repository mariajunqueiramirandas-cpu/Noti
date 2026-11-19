import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { harness, imported, snapshot, command, official, observedPage, rawQuestion, aid, bid, qid, source } from "./api-fixture.mjs";
test("imports only the requested IDs, strips solutions and credentials, and translates the website's filter names", async () => {
  const h = harness(); const value = await h.run({ operation: "import", batch: snapshot() });
  assert.equal(value.ok, true); assert.equal(value.batch.transport, "api-v1");
  assert.equal(value.batch.accountRef, createHash("sha256").update("assaad:test-user").digest("hex"));
  assert.equal(value.batch.questions[0].prompt, "Which river flows through Egypt?");
  assert.doesNotMatch(JSON.stringify(value), /BROWSER_SECRET|SECRET_SOLUTION|"correct"|"explanation"/);
  assert.match(h.calls[1].url, /category_ids=567/); assert.match(h.calls[1].url, /subcategory_ids=719/); assert.match(h.calls[1].url, /tag_ids=422/);
  assert.equal(h.calls.filter(c => c.method === "PATCH").length, 0);
});
test("submits using stable IDs from a normal Assaad tab and accepts only verified official feedback", async () => {
  const batch = await imported(); const h = harness((_url, options) => options.method === "PATCH" ? new Response(JSON.stringify(official())) : null);
  const result = await h.run({ operation: "answer", batch, command: command() });
  assert.equal(result.status, "confirmed"); assert.equal(result.correct, true);
  // Like clicking an option on the website: the session, then the answer. No search first.
  assert.deepEqual(h.calls.map(c => c.method === "PATCH" ? "PATCH" : c.url), ["/api/auth/session", "PATCH"]);
  const writes = h.calls.filter(c => c.method === "PATCH"); assert.equal(writes.length, 1);
  assert.equal(writes[0].url, "https://api.plataformaassaad.com.br/v1/question/answer");
  assert.deepEqual(JSON.parse(writes[0].body), { question_id: qid, alternative_id: aid });
  assert.doesNotMatch(JSON.stringify(result), /BROWSER_SECRET|SECRET_SOLUTION/);
});
test("content changes the page has loaded, or a different account, block the write", async () => {
  const batch = await imported(); const changed = structuredClone(batch); changed.questions[0].alternatives[0].text = "Old option";
  for (const expected of [changed, { ...batch, accountRef: "a".repeat(64) }]) {
    const h = harness(() => null, undefined, observedPage()); const result = await h.run({ operation: "answer", batch: expected, command: command() });
    assert.equal(result.status, "blocked"); assert.equal(h.calls.some(c => c.method === "PATCH"), false);
    assert.equal(h.calls.length, 0, "the website's own session and search are reused, nothing is asked again");
  }
});
test("expired login and read rate limits stop without automatic retries", async () => {
  const batch = await imported();
  for (const status of [401, 429]) {
    const h = harness(() => new Response("{}", { status }));
    assert.equal((await h.run({ operation: "answer", batch, command: command() })).status, "blocked");
    assert.equal(h.calls.length, 1);
  }
});
test("on a page where the website already loaded its session, an answer is a single request", async () => {
  const batch = await imported();
  const h = harness((_url, options) => options.method === "PATCH" ? new Response(JSON.stringify(official())) : null, undefined, observedPage({ questions: [] }));
  assert.equal((await h.run({ operation: "answer", batch, command: command() })).status, "confirmed");
  assert.deepEqual(h.calls.map(c => c.method), ["PATCH"]);
  assert.equal(h.calls.some(c => String(c.url).includes("/question?")), false);
});
test("a login Assaad refuses on the answer is not a write: it's blocked and the session is forgotten", async () => {
  const batch = await imported(); const observed = observedPage();
  const h = harness((_url, options) => options.method === "PATCH" ? new Response("{}", { status: 401 }) : null, undefined, observed);
  const result = await h.run({ operation: "answer", batch, command: command() });
  assert.equal(result.status, "blocked"); assert.equal(result.reason, "login_required");
  assert.equal(observed.forgotten.length, 1); assert.equal(h.calls.filter(c => c.method === "PATCH").length, 1);
});
test("importing what the page shows reuses the website's own search", async () => {
  const h = harness(() => null, undefined, observedPage());
  const value = await h.run({ operation: "import", batch: snapshot() });
  assert.equal(value.ok, true); assert.equal(value.batch.questions[0].prompt, "Which river flows through Egypt?");
  assert.equal(h.calls.length, 0);
  // When the page's search isn't available, the same search is asked once.
  const missing = harness(() => null, undefined, observedPage({ questions: [] }));
  assert.equal((await missing.run({ operation: "import", batch: snapshot() })).ok, true);
  assert.deepEqual(missing.calls.map(c => String(c.url).includes("/question?")), [true]);
});
test("an interrupted write or malformed confirmation remains unknown and is never retried", async () => {
  const batch = await imported();
  for (const handler of [() => { throw new Error("network interrupted"); }, () => new Response(JSON.stringify({ ...official(), correctAnswer: { id: bid } })), () => new Response(JSON.stringify({ ...official(), created: { ...official().created, question_id: bid } }))]) {
    const h = harness((_url, options) => options.method === "PATCH" ? handler() : null);
    assert.equal((await h.run({ operation: "answer", batch, command: command() })).status, "unknown");
    assert.equal(h.calls.filter(c => c.method === "PATCH").length, 1);
  }
});

test("the live flat cookie-session contract connects without a nested user object", async () => {
  const result = await harness().run({ operation: "session" });
  assert.equal(result.ok, true);
  assert.equal(result.accountRef, createHash("sha256").update("assaad:test-user").digest("hex"));
  assert.doesNotMatch(JSON.stringify(result), /BROWSER_SECRET|REFRESH_TOKEN|fixture@example/);
  const malformed = harness(() => new Response('{"session":{"access_token":"BROWSER_SECRET"}}'));
  const error = await malformed.run({ operation: "session" });
  assert.equal(error.error, "invalid_session"); assert.equal(error.stage, "session");
});

test("verified feedback accepts optional response fields without guessing a hidden correct option", async () => {
  const batch = await imported();
  for (const correct of [true, false]) {
    const h = harness((_url, options) => options.method === "PATCH" ? new Response(JSON.stringify({ created: { id: "answer", alternative_id: aid, confirmed: true }, correct })) : null);
    const result = await h.run({ operation: "answer", batch, command: command() });
    assert.equal(result.status, "confirmed"); assert.equal(result.correct, correct);
    assert.equal(result.correctAlternativeId, correct ? aid : null);
    assert.equal(h.calls.filter(c => c.method === "PATCH").length, 1);
  }
});
test("arbitrary origins and unavailable question IDs cannot be used as a request proxy", async () => {
  const batch = await imported(); const h = harness(() => new Response('{"data":[]}'));
  const foreign = { ...batch, source: { ...source, url: "https://evil.example/banco-de-questoes/explorar" } };
  assert.equal((await h.run({ operation: "answer", batch: foreign, command: command() })).status, "blocked");
  assert.equal(h.calls.length, 1);
  const wrongPage = harness(() => null, "https://evil.example/");
  assert.equal((await wrongPage.run({ operation: "session" })).ok, false); assert.equal(wrongPage.calls.length, 0);
});


test("fair-use pacing separates requests and a read-only verification never submits", async () => {
  const batch = await imported(); const h = harness();
  assert.equal((await h.run({ operation: "verify", batch, command: command() })).matched, true);
  assert.equal(h.calls.filter(c => c.method === "PATCH").length, 0);
  assert.ok(h.delays.some(ms => ms > 1500 && ms <= 2000));
  const limited = harness(() => new Response("{}", { status: 429, headers: { "Retry-After": "120" } }));
  const result = await limited.run({ operation: "verify", batch, command: command() });
  assert.equal(result.retryAfterMs, 120000); assert.equal(limited.calls.length, 1);
});


test("a page where the website already reached its API connects without any request", async () => {
  const h = harness(() => null, undefined, observedPage());
  const result = await h.run({ operation: "connect" });
  assert.equal(result.ok, true); assert.equal(result.corsVerified, true); assert.equal(h.calls.length, 0);
});
test("connection preflight proves the authenticated metadata request shape without submitting", async () => {
  const h = harness(url => url.endsWith("/question/metadata") ? new Response('{"tags":[],"category_groups":[]}') : null);
  const result = await h.run({ operation: "connect" });
  assert.equal(result.ok, true); assert.equal(result.corsVerified, true);
  assert.equal(h.calls.filter(c => c.url.endsWith("/question/metadata")).length, 1);
  assert.equal(h.calls.some(c => c.method === "PATCH"), false);
  assert.doesNotMatch(JSON.stringify(result), /BROWSER_SECRET/);
});

test("finder uses the page's existing filter taxonomy and returns only visible option fields", async () => {
  const observed = observedPage();
  observed.metadata = () => ({
    category_groups: [{ name: "Humanas", categories: [{ id: 567, name: "História", subcategories: [{ id: 719, name: "Idade Antiga" }] }] }],
    lone_categories: [{ id: 36, name: "Matemática" }],
    tags: [{ id: 422, name: "Egito", internal: "PRIVATE_VALUE" }],
    institutions: [{ id: 4, name: "ENEM" }],
    years: [2025, "2024"],
  });
  const h = harness(() => null, undefined, observed);
  const result = await h.run({ operation: "metadata" });
  assert.equal(result.ok, true);
  assert.deepEqual(result.options, {
    categories: [{ id: "36", label: "Matemática", topics: [] }, { id: "567", label: "História", topics: [{ id: "719", label: "Idade Antiga" }] }],
    tags: [{ id: "422", label: "Egito" }],
    institutions: [{ id: "4", label: "ENEM" }],
    years: [{ id: "2025", label: "2025" }, { id: "2024", label: "2024" }],
  });
  assert.equal(h.calls.length, 0);
});

test("finder fetches exactly one bounded published page with the selected filters", async () => {
  const h = harness(() => new Response(JSON.stringify({ data: [rawQuestion()], meta: { total: 35, lastPage: 2 } })), undefined, observedPage());
  const filters = { search: "Egito & Roma", category_id: "567", subcategory_id: "719", tag_ids: "422", institution_id: "4", difficulties: "3", years: "2025", is_published: "false" };
  const result = await h.run({ operation: "search", page: 2, filters });
  assert.equal(result.ok, true);
  assert.equal(result.total, 35);
  assert.equal(result.lastPage, 2);
  assert.equal(result.batch.transport, "api-v1");
  assert.equal(result.batch.source.page, "2");
  assert.equal(new URL(result.batch.source.url).searchParams.get("category_id"), "567");
  assert.equal(result.batch.questions[0].id, qid);
  assert.doesNotMatch(JSON.stringify(result), /BROWSER_SECRET|SECRET_SOLUTION|"correct"|"explanation"/);
  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].method, "GET");
  const url = new URL(h.calls[0].url);
  assert.equal(url.origin + url.pathname, "https://api.plataformaassaad.com.br/v1/question");
  assert.deepEqual(Object.fromEntries(url.searchParams), {
    page: "2", perPage: "20", is_published: "true", category_ids: "567", subcategory_ids: "719",
    institution_ids: "4", tag_ids: "422", difficulties: "3", years: "2025", search: "Egito & Roma",
  });
});

test("finder handles empty pages and stops after an expired session", async () => {
  const empty = harness(() => new Response('{"data":[],"meta":{"total":0}}'), undefined, observedPage());
  const result = await empty.run({ operation: "search" });
  assert.equal(result.ok, true);
  assert.deepEqual(result.batch.questions, []);
  assert.equal(result.lastPage, 0);
  const observed = observedPage();
  const expired = harness(() => new Response("{}", { status: 401 }), undefined, observed);
  assert.equal((await expired.run({ operation: "search" })).error, "login_required");
  assert.equal(expired.calls.length, 1);
  assert.equal(observed.forgotten.length, 1);
  const invalid = harness();
  assert.equal((await invalid.run({ operation: "search", page: 0 })).error, "invalid_search");
  assert.equal(invalid.calls.length, 0);
});
