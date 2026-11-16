# Study boards

Study boards combine an endless native ink canvas with saved study material.
Cloud generation and grading services, their upload flows, and API-key settings
have been removed. On-device handwriting-to-text (Transcribe) remains available.
Existing saved content and drawings are retained.

## Local study

- Review existing multiple-choice items and flip/rate flash cards.
- Reveal saved hints, answers, and source-page previews.
- Self-assess a written answer with Mark correct / Mark partly right / Mark not yet.
- Use spaced reviews, timed practice exams, and printable score cards.
- Pin questions and source pages to the canvas, arrange cards by topic, and export PDF.

These actions use local study state; there is no remote model or cloud grading.
Previously saved feedback remains historical data, not a new model invocation.

## Plataforma Assaad

Open Plataforma Assaad in Noti’s library and pair its Edge extension with a
six-digit code. The extension’s popup handles pairing only. Import the questions
currently displayed on Assaad using Enviar questões ao Noti.

On the native canvas, alternative selections save as drafts. Saved sets keeps
multiple imports across app restarts. Explicitly approve one answer with Confirm
in Assaad, or queue all eligible drafts using Saved sets → Submit saved answers.
Pause/resume/cancel are available. Drawings stay local; anonymous image copies
are saved when possible, with an offline image count.

Approved answers wait for the selected browser and a valid Assaad login.
The original question page need not stay open for API-backed imports. The
shared browser adapter verifies account, question UUID, and content, then submits through
the question-bank endpoint. All Assaad requests originate from the user’s own
browser/network connection; the Bun relay never proxies them or receives
Assaad cookies/access tokens. Fixed fair-use pacing and rate-limit cooldowns apply.

A timeout or uncertain response after a write requires user review before another
attempt. Pairing renewal or relay expiry does not erase the device’s saved sets.

Read [the endpoint investigation](integrations/plataforma-assaad-api.md),
[extension guide](../extensions/assaad-reader/README.md), and
[deployment guide](../server/README.md) for contracts, retention, limits, and validation.

## Implementation

Local models and scheduling are in `app/src/main/kotlin/app/noti/study`;
manual study UI is in `ui/study`; saved platform sessions are in `assaad`
and `ui/assaad`. Pairing credentials use a device-bound encrypted store separate
from study content. Old cloud credentials are cleared on upgrade. Legacy field
names in the pairing store are read only to preserve existing paired sessions.
