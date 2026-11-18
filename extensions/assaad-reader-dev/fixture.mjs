// Original sample content with the DOM structure observed in Edge. No question
// bank content, credentials, or account data is included in this fixture.
export function questionCard(id = "sample-question", extra = "") {
  return `<div data-slot="card">
    <div data-slot="card-header"><p>INSTITUIÇÃO EXEMPLO | História | Idade Antiga | 2026</p>
      <button>Reportar problema</button><span>ACCOUNT SECRET</span></div>
    <div data-slot="card-content">
      <div><span data-slot="badge">Egito</span><span data-slot="badge">Dificuldade: Fácil</span></div>
      <div><div class="prose"><p>Qual rio favoreceu a agricultura egípcia?</p>
        <p>Considere a ilustração.<img src="https://example.com/nilo.webp" alt="Mapa do Nilo"></p>
        <div hidden>HIDDEN PROMPT SECRET</div><span class="sr-only">SCREENREADER SECRET</span>
        <span style="display:none">CSS SECRET</span>${extra}</div></div>
      <div role="radiogroup" data-slot="radio-group" id="${id}">
        <div><label for="${id}-a"><div><div><button role="radio" id="${id}-a" aria-checked="false"></button><span>A</span></div>
          <div><label for="${id}-a">Rio Nilo</label></div></div></label><button>Marcar como incorreta</button></div>
        <div><label for="${id}-b"><div><div><button role="radio" id="${id}-b" aria-checked="true"></button><span>B</span></div>
          <div><label for="${id}-b"><p>Rio Amazonas</p><span hidden>HIDDEN ANSWER SECRET</span></label></div></div></label></div>
      </div>
      <div class="sr-only">Selecione uma alternativa e confirme.</div>
      <div><button>Confirmar resposta</button><div hidden>HIDDEN SOLUTION SECRET</div><p>VISIBLE EXPLANATION OUTSIDE PROMPT</p></div>
    </div>
  </div>`;
}

export function fixture() {
  return `<!doctype html><html lang="pt-BR"><head><title>Fixture · Assaad Reader</title></head><body>
    <nav>PRIVATE ACCOUNT TEXT</nav>
    <div role="radiogroup" data-slot="toggle-group"><button role="radio">Não respondidas</button><button role="radio">Respondidas</button></div>
    ${questionCard()}
    <nav aria-label="pagination"><a aria-current="page">1</a><a aria-label="Ir para a próxima página">2</a></nav>
  </body></html>`;
}
