export function asText(snapshot) {
  const lines = ["Assaad Reader", snapshot.source.url,
    `Página: ${snapshot.source.page || "—"} · ${snapshot.questions.length} questões`,
    `Leitura: ${snapshot.capturedAt}`, ""];
  snapshot.questions.forEach((question, index) => {
    lines.push(`QUESTÃO ${index + 1}`, question.source);
    if (question.tags.length) lines.push(question.tags.join(" · "));
    if (question.difficulty) lines.push(`Dificuldade: ${question.difficulty}`);
    lines.push("", question.prompt);
    addImages(lines, question.images);
    for (const option of question.alternatives) {
      lines.push("", `${option.label}) ${option.text}`);
      addImages(lines, option.images);
    }
    lines.push("", "────────────────────────────────", "");
  });
  if (snapshot.skipped) lines.push(`Aviso: ${snapshot.skipped} questão(ões) com estrutura não reconhecida foram omitidas.`);
  return lines.join("\n");
}

function addImages(lines, images) {
  for (const image of images) lines.push(`Imagem${image.alt ? ` (${image.alt})` : ""}: ${image.url || "sem URL exportável"}`);
}

export function exportName(snapshot, extension) {
  const stamp = snapshot.capturedAt.replace(/[:.]/g, "-");
  const page = snapshot.source.page?.replace(/[^0-9]/g, "") || "atual";
  return `assaad-pagina-${page}-${stamp}.${extension}`;
}
