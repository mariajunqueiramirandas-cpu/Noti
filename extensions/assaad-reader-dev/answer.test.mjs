import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { Window } from "happy-dom";
import { fixture } from "./fixture.mjs";
const root = new URL("../assaad-reader/", import.meta.url);
const source = readFileSync(new URL("reader.js", root), "utf8").replace("export function", "function");
const { answerOnPage } = await import(`data:text/javascript;base64,${Buffer.from(readFileSync(new URL("answer.js", root))).toString("base64")}`);
function setup() {
  const window = new Window({ url: "https://app.plataformaassaad.com.br/banco-de-questoes/explorar" }); window.document.write(fixture());
  const read = () => window.eval(source + "\nreadQuestions();");
  const expected = read().questions[0];
  const command = { commandId: "test-command", questionId: expected.id, alternativeId: expected.alternatives[0].id };
  const confirm = [...window.document.querySelectorAll("button")].find(button => button.textContent === "Confirmar resposta");
  confirm.setAttribute("aria-label", "Confirmar resposta"); confirm.disabled = true;
  const feedback = window.document.querySelector(".sr-only:last-of-type") || window.document.createElement("div");
  feedback.setAttribute("aria-live", "polite");
  confirm.parentElement.append(feedback);
  return { window, read, expected, command, confirm, feedback };
}
test("confirmation uses the rendered radio and button and reads official feedback", async () => {
  const { window, read, expected, command, confirm, feedback } = setup(); let submissions = 0;
  const radio = window.document.getElementById(command.alternativeId);
  radio.addEventListener("click", () => { radio.setAttribute("aria-checked", "true"); confirm.disabled = false; });
  confirm.addEventListener("click", () => { submissions++; confirm.disabled = true; feedback.textContent = "Resposta correta."; });
  const result = await answerOnPage(command, expected, read, window.document, async () => {});
  assert.equal(submissions, 1); assert.equal(result.status, "confirmed"); assert.equal(result.correct, true); assert.equal(result.correctAlternativeId, command.alternativeId);
});
test("stale questions and disabled controls fail without invoking controls", async () => {
  const app = setup(); let clicks = 0;
  app.window.document.getElementById(app.command.alternativeId).click = () => { clicks++; };
  app.confirm.click = () => { clicks++; };
  assert.equal((await answerOnPage(app.command, { ...app.expected, prompt: "Changed" }, app.read, app.window.document, async () => {})).status, "failed");
  app.window.document.getElementById(app.command.alternativeId).disabled = true;
  assert.equal((await answerOnPage(app.command, app.expected, app.read, app.window.document, async () => {})).status, "failed"); assert.equal(clicks, 0);
});
test("a response timeout is unknown and never retries the submission", async () => {
  const app = setup(); let clicks = 0;
  const radio = app.window.document.getElementById(app.command.alternativeId);
  radio.addEventListener("click", () => { radio.setAttribute("aria-checked", "true"); app.confirm.disabled = false; });
  app.confirm.addEventListener("click", () => { clicks++; });
  const result = await answerOnPage(app.command, app.expected, app.read, app.window.document, async () => {});
  assert.equal(result.status, "unknown"); assert.equal(clicks, 1); assert.equal(result.correct, null);
});
