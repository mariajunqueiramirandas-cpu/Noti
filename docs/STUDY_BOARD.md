# Study tools

Every notebook in Noti has a floating **Study** sheet for flash cards, practice questions,
imported past exams, and Plataforma Assaad questions. Your canvas, decks, exams, and review
history stay on your device.

## Build a deck on any notebook

1. Open any notebook and tap **Study** in the top bar.
2. On the **Deck** tab, use **Add flash card** or **Add question** to create an item.
3. For a flash card, enter the front and back. For a question, choose a written answer or four
   multiple-choice options and mark the correct one. Add a topic if you want to group related items.

The sheet’s **⋯** menu lets you edit the current item or open the whole **Deck**. Tap a deck
item to open it on the sheet. Delete an item from the deck to remove its printed card from the
canvas. Your handwriting stays on the canvas.

## Practice and review

- **Flash cards.** Try to recall the answer, tap **Show answer**, and choose **Again**, **Hard**,
  **Good**, or **Easy**.
- **Multiple choice.** Tap an option to check it against the saved answer.
- **Written questions.** Work on the canvas, then tap **Answer** to compare your work with the
  saved answer. Rate your work as **Correct**, **Partly right**, or **Not yet**.
- **Sources.** Saved hints and worked solutions are available where present. **Source** opens a
  cited PDF page still in your library, and **Place on board** pins it next to your ink.

Reviews are scheduled using FSRS. **Study** opens due items first, followed by new items. Arrows
move through the active list. **Try this one again** clears the current attempt and keeps the
review schedule.

## Use the canvas

On any Endless notebook, tap **Print** to pin the current card (from **Deck**, **Assaad**, or
**Exams**) right where the floating sheet shows it. Drag the sheet by its header on tablets, or
collapse it when you need more writing room. Tap a pinned card on the canvas to reopen it, move
it, or remove it. **Arrange cards by topic** groups pinned cards in columns. PDF export includes
pinned cards, source pages, and exam scores.

## Past exams (any exam PDF)

Open **Study** in the library sidebar to manage **Past exams** and **Plataforma Assaad** in one place.

- **AI-powered import.** Select any exam PDF (ENEM, FUVEST, UNICAMP, UNESP, ITA, university
  exams, or mock exams). AI automatically extracts the exam title, institution, year, details,
  subjects, written and multiple-choice questions, topics, and answer keys when present. Review
  and edit anything before saving.
- **Dynamic subjects, topics, and lessons.** Questions are grouped by whatever subjects appear
  in your imported PDFs. Generate step-by-step AI lessons and earn XP toward your level and
  daily goal.
- **Solve on any canvas.** Access your imported exams from the **Exams** tab in the Study sheet
  on any notebook, or tap **Resolver no quadro** from the Study hub to choose which notebook to
  open (or create a new one).
- Legacy `fuvest.json` catalogs migrate automatically to `exams.json` on first load.

## Timed practice exams

On the **Deck** tab, choose **Start an exam…** from the sheet’s **⋯** menu, then pick a time,
question count, and optional topics. Choose answers for multiple-choice questions on the sheet.
For written questions, work on the canvas and tap **Mark answered** for each one you attempt.

Finished exams show multiple-choice results and the saved answers for written questions you
marked. Compare your written answers and record the results. Use **Last exam results** in the
sheet’s menu to return to the review. When you finish assessing the written answers, you can
print a score card on the canvas.

## Plataforma Assaad

Access Plataforma Assaad either from **Study → Assaad** in the library sidebar or directly from
the **Assaad** tab inside the Study sheet on **any** notebook.

- Choose **On this tablet** to sign into Assaad inside Noti and search questions with Noti’s
  filters, or pair the **Edge / Chrome** extension with a six-digit code.
- Opening the Assaad browser from inside a notebook brings fetched questions straight back to
  that same notebook. Opening from the Study hub lets you choose which notebook to use.
- Tapping an option saves it as a draft (shown in lilac); **Check with Assaad** sends it one at
  a time while connected. Assaad’s verdict comes back onto the card (mint for right, peach for
  wrong) and schedules review. **Print** pins the question (with images) onto your current canvas.
- Answers never queue up silently offline, and Assaad questions stay out of local timed practice
  exams. All Assaad requests originate from the user’s own browser connection; the relay never
  proxies them or receives Assaad cookies/access tokens.

Read [the endpoint investigation](integrations/plataforma-assaad-api.md),
[extension guide](../extensions/assaad-reader/README.md), and
[deployment guide](../server/README.md) for contracts, retention, limits, and validation.

## Implementation

Local deck and scheduling live in `app/src/main/kotlin/app/noti/study`; unified floating sheet UI
lives in `ui/study`; generalized past exams, AI import, lessons, and XP journey live in `exams`
and `ui/exams`; Plataforma Assaad sessions live in `assaad` and `ui/assaad`.
