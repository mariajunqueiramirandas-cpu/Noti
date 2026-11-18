/**
 * One synchronous, read-only DOM snapshot. This function is self-contained because
 * chrome.scripting serializes it into the tab's ISOLATED execution world.
 * Keep helpers inside it; never fetch, click, mutate, or inspect application state.
 */
export function readQuestions() {
  if (location.origin !== "https://app.plataformaassaad.com.br" ||
      !/^\/banco-de-questoes\/explorar\/?$/.test(location.pathname)) {
    return { ok: false, code: "wrong-page" };
  }

  const styles = new Map();
  const visibility = new Map();
  const blockTags = new Set(["P", "DIV", "SECTION", "BLOCKQUOTE", "LI", "TR", "H1", "H2", "H3", "H4"]);
  const skippedTags = new Set(["SCRIPT", "STYLE", "TEMPLATE", "NOSCRIPT", "SVG", "BUTTON", "INPUT", "TEXTAREA", "SELECT"]);
  const clean = (text) => text.replace(/\u00a0/g, " ").replace(/[\t \f\v]+/g, " ")
    .replace(/ *\n */g, "\n").replace(/\n{3,}/g, "\n\n").trim();
  function styleOf(element) {
    if (!styles.has(element)) styles.set(element, getComputedStyle(element));
    return styles.get(element);
  }
  function visible(element) {
    if (!element) return true;
    if (visibility.has(element)) return visibility.get(element);
    const style = styleOf(element);
    const value = !element.hidden && element.getAttribute("aria-hidden") !== "true" &&
      !element.classList.contains("sr-only") && style.display !== "none" &&
      style.visibility !== "hidden" && style.visibility !== "collapse" &&
      style.contentVisibility !== "hidden" && style.opacity !== "0" && visible(element.parentElement);
    visibility.set(element, value);
    return value;
  }
  function imageUrl(element) {
    try {
      const raw = element.currentSrc || element.getAttribute("src");
      if (!raw) return null;
      const url = new URL(raw, location.href);
      return ["http:", "https:"].includes(url.protocol) ? url.href : null;
    } catch {
      return null;
    }
  }
  function content(root) {
    const images = [];
    function walk(node) {
      if (node.nodeType === 3) return node.nodeValue.replace(/\s+/g, " ");
      if (node.nodeType !== 1 || !visible(node) || skippedTags.has(node.tagName)) return "";
      if (node.tagName === "BR") return "\n";
      if (node.tagName === "IMG") {
        const alt = clean(node.getAttribute("alt") || "");
        images.push({ alt, url: imageUrl(node) });
        return ` [Imagem${alt ? `: ${alt}` : ""}] `;
      }
      // KaTeX exposes the same formula twice. Its annotation is the source of
      // the visible equation, not a hidden question/answer or framework state.
      if (node.classList.contains("katex")) {
        const tex = node.querySelector('annotation[encoding="application/x-tex"]');
        if (tex) return ` \\(${tex.textContent.trim()}\\) `;
      }
      const text = Array.from(node.childNodes, walk).join("");
      if (blockTags.has(node.tagName)) return `\n${text}\n`;
      if (["TD", "TH"].includes(node.tagName)) return `${text} | `;
      return text;
    }
    return { text: root ? clean(walk(root)) : "", images };
  }

  const groups = Array.from(document.querySelectorAll('[role="radiogroup"][data-slot="radio-group"]'))
    .filter(visible);
  const questions = [];
  let skipped = 0;
  for (const group of groups) {
    const card = group.closest('[data-slot="card"]');
    const promptElement = group.previousElementSibling;
    const prose = promptElement?.matches(".prose") ? promptElement : promptElement?.querySelector(".prose");
    const radios = Array.from(group.querySelectorAll('[role="radio"]'))
      .filter((radio) => radio.closest('[role="radiogroup"]') === group && visible(radio));
    // Fail closed if the known layout changes, rather than exporting unrelated
    // text or accidentally including answer explanations from the card footer.
    if (!card || !group.id || radios.some(radio => !radio.id) || group.parentElement?.getAttribute("data-slot") !== "card-content" ||
        !prose || promptElement.querySelector('[role="radiogroup"], button, input') ||
        radios.length < 2) {
      skipped++;
      continue;
    }
    const prompt = content(prose);
    const alternatives = radios.map((radio, index) => {
      const labels = Array.from(group.querySelectorAll("label[for]"))
        .filter((label) => label.htmlFor === radio.id && visible(label));
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
    if ((!prompt.text && !prompt.images.length) || alternatives.some((option) => !option.text && !option.images.length)) {
      skipped++;
      continue;
    }
    const metadata = promptElement.previousElementSibling;
    const badges = Array.from(metadata?.querySelectorAll('[data-slot="badge"]') || [])
      .map((badge) => content(badge).text).filter(Boolean);
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

  // Preserve study filters, never arbitrary query parameters or URL fragments.
  const pageUrl = new URL(location.origin + location.pathname);
  const query = new URLSearchParams(location.search);
  for (const key of ["category_id", "subcategory_id", "institution_id", "tag_ids", "page", "page_size", "search", "personal_filters", "difficulties", "years", "folder_id"]) {
    for (const value of query.getAll(key)) pageUrl.searchParams.append(key, value);
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
