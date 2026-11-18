# Study boards

A Study board is an endless canvas with a floating sheet for flash cards and practice questions.
The canvas, deck, and review history stay on your device.

## Build a deck

1. Choose **New → Study board** in the library. The board starts on grid paper.
2. Tap **Study** in the editor. Use **Add flash card** or **Add question** to create an item.
3. For a flash card, enter the front and back. For a question, choose a written answer or four
   multiple-choice options and mark the correct one. Add a topic if you want to group related items.

The sheet’s **⋯** menu lets you edit the current item or open the whole **Deck**. Tap a deck
item to open it on the sheet. Delete an item from the deck to remove its printed card from the
board. Your handwriting stays on the canvas.

## Practice and review

- **Flash cards.** Try to recall the answer, tap **Show answer**, and choose **Again**, **Hard**,
  **Good**, or **Easy**.
- **Multiple choice.** Tap an option to check it against the saved answer.
- **Written questions.** Work on the canvas, then tap **Answer** to compare your work with the
  saved answer. Rate your work as **Correct**, **Partly right**, or **Not yet**.
- **Existing decks.** Saved hints and worked solutions are available where present. **Source**
  opens a cited PDF page still in your library, and **Place on board** pins it next to your ink.

Reviews are scheduled using FSRS. **Study** opens due items first, followed by new items. Arrows
move through the deck. **Try this one again** clears the current attempt and keeps the review schedule.

## Use the canvas

Tap **Print** to pin the current study card as it appears on the floating sheet. Drag the sheet
by its header on tablets, or collapse it when you need more writing room. Tap a pinned card on
the canvas to reopen it, move it, or remove it. **Arrange cards by topic** groups the pinned
cards in columns. PDF export includes pinned cards, source pages, and exam scores.

## Take an exam

Choose **Start an exam** from the sheet’s **⋯** menu, then pick a time, question count, and
optional topics. Choose answers for multiple-choice questions on the sheet. For written
questions, work on the canvas and tap **Mark answered** for each one you attempt.

Finished exams show multiple-choice results and the saved answers for written questions you
marked. Compare your written answers and record the results. Use **Last exam results** in the
sheet’s menu to return to the review. When you finish assessing the written answers, you can
print a score card on the board.

## Plataforma Assaad

Open Plataforma Assaad in Noti’s library and pair its Edge extension with a
six-digit code. The extension’s popup handles pairing only. Import the questions
currently displayed on Assaad using Enviar questões ao Noti.

Saved questions open on the Assaad board's ordinary study sheet, as multiple
choice cards with their pictures in place. A new set opens the sheet on its first
question. Tapping an option saves it as a draft (shown in lilac, without right or
wrong); **Check with Assaad** sends it. Assaad's verdict comes back onto the card
like any other checked choice (mint for the right option, peach for a wrong pick)
and schedules the question for review. Print pins it to the board, pictures
included. The sheet's menu opens Assaad and the saved sets. Answers go one at a
time: each is sent by its own tap, only while Assaad is connected, and only when no
other answer is on its way. While Assaad isn't connected, picks stay picks; nothing
queues up to send later or in a run. Assaad questions stay out of practice exams, since their answers are
real submissions. Drawings stay local; anonymous image copies are saved when
possible, with an offline image count.

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

## Saved data

Decks, review history, source references, and printed materials are saved in a JSON file beside
the board’s ink. Previously saved boards, cards, grades, citations, and printed pages use the
same file format and remain readable. The deck and exam models live in `study/Deck.kt`, the
canvas layer and PDF export in `study/BoardLayer.kt` and `study/BoardExport.kt`, and the
floating sheet in `ui/study/`.

## Implementation

Local models and scheduling are in `app/src/main/kotlin/app/noti/study`;
manual study UI is in `ui/study`; saved platform sessions are in `assaad`
and `ui/assaad`. Pairing credentials use a device-bound encrypted store separate
from study content. Old cloud credentials are cleared on upgrade. Legacy field
names in the pairing store are read only to preserve existing paired sessions.
