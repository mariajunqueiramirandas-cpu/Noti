import vm from "node:vm";
import { readFileSync } from "node:fs";
import { webcrypto, createHash } from "node:crypto";
import { Window } from "happy-dom";
const code = readFileSync(new URL("../assaad-reader/platform-api.js", import.meta.url), "utf8").replace("export async function", "async function");
export const qid = "00000000-0000-4000-8000-000000000001", aid = "00000000-0000-4000-8000-000000000002", bid = "00000000-0000-4000-8000-000000000003";
export const rawQuestion = () => ({ id: qid, text: "<p>Which river flows through Egypt?</p>", institution: { name: "Test" }, category: { name: "History" }, year: 2026, difficulty: 1, tag_links: [],
  explanation: "SECRET_SOLUTION", alternatives: [{ id: aid, label: "A", text: "Nile", correct: true }, { id: bid, label: "B", text: "Amazon", correct: false }] });
export const source = { url: "https://app.plataformaassaad.com.br/banco-de-questoes/explorar?category_id=567&subcategory_id=719&tag_ids=422", page: "1" };
export const snapshot = () => ({ batchId: "00000000-0000-4000-8000-000000000010", capturedAt: "2026-11-16T00:00:00.000Z", source, questions: [{ id: qid, alternatives: [{ id: aid }, { id: bid }] }] });
export function harness(handler = () => null, url = "https://app.plataformaassaad.com.br/") {
  const window = new Window({ url }); const calls = [], delays = [];
  const fetch = async (url, options = {}) => {
    calls.push({ url, ...options });
    const override = await handler(url, options);
    if (override) return override;
    return new Response(JSON.stringify(url === "/api/auth/session" ? { session: { access_token: "BROWSER_SECRET", user: { id: "test-user" } } } : { data: [rawQuestion()], meta: { total: 1 } }), { headers: { "Content-Type": "application/json" } });
  };
  const run = vm.runInNewContext(code + ";runAssaadOperation", { fetch, location: new URL(url), URL, URLSearchParams, DOMParser: window.DOMParser, TextEncoder, TextDecoder, crypto: webcrypto, AbortSignal, setTimeout: (fn, ms) => { delays.push(ms); queueMicrotask(fn); return 0; } });
  return { run: async input => JSON.parse(JSON.stringify(await run(input))), calls, delays };
}
export async function imported() { return (await harness().run({ operation: "import", batch: snapshot() })).batch; }
export const command = () => ({ commandId: "00000000-0000-4000-8000-000000000020", batchId: snapshot().batchId, questionId: qid, alternativeId: aid });
export const official = () => ({ created: { id: "answer", question_id: qid, alternative_id: aid, confirmed: true }, correct: true, correctAnswer: { id: aid }, explanation: "SECRET_SOLUTION" });
