/** Submit through the platform's own controls. No private endpoint calls. */
export async function answerOnPage(command, expected, read, document, wait = ms => new Promise(resolve => setTimeout(resolve, ms))) {
  const result = (status, feedback, correct = null, correctAlternativeId = null) => ({ commandId: command.commandId, status, correct, correctAlternativeId, feedback });
  const snapshot = read();
  const current = snapshot.ok && snapshot.questions.find(q => q.id === command.questionId);
  if (!current || current.prompt !== expected.prompt || !current.alternatives.some(a => a.id === command.alternativeId && expected.alternatives.some(e => e.id === a.id && e.text === a.text))) return result("failed", "A questão mudou ou saiu desta página. Envie as questões novamente.");
  const group = document.getElementById(command.questionId), radio = document.getElementById(command.alternativeId);
  const card = group?.closest('[data-slot="card"]');
  const confirm = card?.querySelector('button[aria-label="Confirmar resposta"]');
  if (!group || !radio || !group.contains(radio) || !confirm || radio.disabled || group.getAttribute("data-disabled") !== null) return result("failed", "A questão já foi respondida ou não está disponível para confirmação.");
  radio.click();
  for (let i = 0; i < 15 && confirm.disabled; i++) await wait(100);
  if (confirm.disabled || radio.getAttribute("aria-checked") !== "true") return result("failed", "A plataforma não aceitou a seleção. Confira a questão no navegador.");
  confirm.click();
  for (let i = 0; i < 100; i++) {
    await wait(100);
    const feedback = card.querySelector('[aria-live="polite"]')?.textContent?.trim() || "";
    if (/^Resposta (correta|incorreta)\./i.test(feedback)) {
      const correct = /^Resposta correta\./i.test(feedback);
      const right = Array.from(group.querySelectorAll('label[for]')).find(label => Array.from(label.children).some(child =>
        child.classList.contains("text-green-500") && child.innerText?.trim() === "Resposta correta!"));
      return result("confirmed", feedback, correct, right?.htmlFor || (correct ? command.alternativeId : null));
    }
  }
  return result("unknown", "A confirmação não retornou a tempo. Confira a plataforma; a resposta não será reenviada automaticamente.");
}
