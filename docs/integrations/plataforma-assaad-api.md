# Plataforma Assaad endpoint investigation

Investigated **2026-11-16** for Noti’s question library, offline drafts, and delayed submissions.

## Evidence and limits

These are **internal website contracts**, not a verified public integration API. Findings come from the production JavaScript loaded by the user’s signed-in Edge dashboard and question bank. The page displayed 20 questions out of 35 matching the supplied História / Idade Antiga / Egito search. Its question and alternative DOM IDs are UUIDs; the client passes those same identifiers to its answer API.

Initial source research used a normal navigation to the supplied question page and downloaded each observed first-party JavaScript asset once (71 assets, about 5.3 MB). That source investigation involved no endpoint enumeration, guessed-route probing, OTP request, answer submission, bookmarking, account modification, or admin call. No browser cookies, live session tokens, HAR files, private profile data, or real question contents are included in this document or fixtures. Ordinary website traffic can be logged; this integration makes no invisibility claim.

**Follow-up live validation:** the user's signed-in Edge session returned HTTP 200 for `GET /api/auth/session`, `GET /question/metadata`, and the website-observed `GET /question?page=1&perPage=20&category_ids=567&is_published=true`. Question search returned 20 rows. The packaged DOM reader also read 20 cards with zero skipped cards. Only field names/types, counts, and status codes were inspected; credential values remained in the browser. No answer was submitted. The approved one-answer check was interrupted before execution when computer use was stopped.

**Confidence:** endpoint names, HTTP methods, filters, and call-site bodies below are source-confirmed; those three read response shapes are now live-verified. Other response contracts, permissions, write behavior, and idempotency remain unverified. Unit and loopback integration tests exercise sanitized fixtures; they do not establish a live answer submission.

## Bases and authentication

- Website: `https://app.plataformaassaad.com.br`.
- REST: `https://api.plataformaassaad.com.br/v1`.
- The website creates an Axios client with a 30-second timeout and adds `Authorization: Bearer <current access token>`.
- Its same-origin `GET /api/auth/session` includes cookies and returns `{session}`; the source checks HTTP 401 with `error: "session_expired"`. The live cookie session is flat: `{id,email,access_token,refresh_token,expires_in,token_type}`. Its account identifier is `session.id`, not `session.user.id`. A sanitized fixture reproduces this structure without production values. The old nested-user assumption caused the false tablet login error and has been removed.
- Cookie refresh is `POST /api/auth/refresh`, with no JSON body shown. The client reads `{session}`, coordinates refreshes across tabs, and handles `Retry-After`. Noti leaves refresh and login to the website to avoid competing token rotations.
- `POST /api/auth/session` accepts `{session}` and persists login; `DELETE /api/auth/session` logs out. The integration never calls either.
- The adapter obtains a short-lived token inside the Assaad page context. Only sanitized question content, a SHA-256 account reference, and official submission feedback leave that context. The extension and relay never store Assaad tokens/cookies. In tablet mode, the real WebView keeps its own website cookies in Android app-private browser storage, excluded from backup and device transfer; native Noti code never exports them.

## Browser-only calls and fair-use limits

Every Assaad HTTP request runs inside the user’s authenticated Edge page, using the
user’s network connection (including any VPN/proxy the user configured). The Bun relay
never proxies those calls or stores Assaad credentials. Fixed packaged operations are
serialized; operation starts are separated by at least five seconds, individual
requests by two seconds, and answers by ten seconds (at most six per minute).
Background readiness probes are coalesced and reuse status for thirty seconds on
an unchanged page. HTTP 429 honors Retry-After, with at least a sixty-second pause
persisted in browser storage. User resume cannot bypass that deadline. These fixed
limits reduce traffic; no user-agent/IP spoofing or detection bypass is used.

## Reconnection behavior

Imports wait for the relay's authenticated session acknowledgement, rather than
assuming an open socket is ready. If the connection drops before a question-set
acknowledgement arrives, only that immutable snapshot can be replayed using the
same batch ID. Answer commands never use this replay path. Server publication
errors include the batch ID and reach the UI immediately. A connection replaced
by another instance stops retrying until the user explicitly reconnects, preventing
two duplicate extensions from repeatedly disconnecting each other. Page notices
clear obsolete connection errors when authoritative status recovers.

## Single-tablet browser mode

Choose **Plataforma Assaad → On this tablet → Open Assaad** and sign into the real
website. Noti notices the login on its own (Assaad is a single-page site, so the
check also runs when its address changes without a page load). On a loaded question
search, **Study on canvas** saves that page's questions and opens them on the Assaad
board's study sheet. **Canvas** or Close returns to Noti and keeps that same WebView
parked, invisible, with its authenticated page alive during the foreground Noti
session. Open, the browser is a full-screen dialog window, so editor chrome and the
ink window can't cover it. Links that leave Assaad open in the system browser. Edge, the extension, code pairing, and the relay are unnecessary in this
mode. Saved sets and the submission queue are shared with the Edge mode.

The APK packages the same `platform-api.js` and DOM reader as the extension.
JavaScript calls `fetch` from the real HTTPS Assaad page. Standard browser CORS and
preflight checks apply; CORS is neither emulated nor disabled. A read-only
`GET /api/auth/session` plus authenticated `GET /question/metadata` check must
succeed before queued answers can run. An import also proves its own authenticated
read. A CORS failure blocks connection, with no native HTTP or server fallback.

A one-use message port targets only the exact Assaad main-frame origin. The
WebView uses its default User-Agent and rejects foreign top-level navigation,
mixed content, file/content access, and SSL-error bypasses. There is no injected
native JavaScript interface, cookie export, or request proxy. Assaad may identify
the WebView and log its ordinary requests; it is not guaranteed to look identical
to a desktop browser or manual interaction.

The app retains the minimized browser while foregrounded, not as an Android
background service. Login expiry, a renderer failure, and interrupted writes need
user attention. On an app restart, reopen the browser and authenticate if needed;
text, drafts, and cached images remain available offline. The same fixed pacing,
account/content checks, rate-limit cooldowns, and no-automatic-write-retry rule apply.

**Validation limit:** tests cover packaging, origin guards, WebView security
settings, queue dispatch without relay frames, and the shared adapter against
fixtures. A physical-device Assaad login, live CORS response, and real answer
mutation have not been tested. A source-confirmed endpoint can still change or
reject a WebView session.

## Question-bank read contract

```http
GET https://api.plataformaassaad.com.br/v1/question?page=1&perPage=20&is_published=true&category_ids=567&subcategory_ids=719&tag_ids=422
Authorization: Bearer <browser-held token>
```

The client expects `{data: Question[], meta: {currentPage,lastPage,next,prev,total}}`. Its array serializer repeats keys (`tag_ids=422&tag_ids=423`), omits nulls, and suppresses empty arrays. The website URL uses comma-separated singular query names; these must be translated:

| Website query | API parameter |
| --- | --- |
| `category_id` | repeated `category_ids` |
| `subcategory_id` | repeated `subcategory_ids` |
| `institution_id` | repeated `institution_ids` |
| `tag_ids`, `difficulties`, `years` | same names, repeated keys |
| `personal_filters` | repeated `personal` |
| `page_size` | `perPage` |
| `page`, `search`, `folder_id` | same names |

Client rendering uses `Question.id`, `text` (HTML/LaTeX), optional `image`, `alternatives[].{id,label,text,image}`, institution/category/subcategory names, year, difficulty, and `tag_links[].tag.name`. Additional fields can exist. The adapter constructs a new allowlisted object and omits explanations, correctness flags, answer keys, profile data, and unknown fields.

**There is no source-confirmed student `GET /question/{id}` in the inspected bundles. Do not invent that route or use `/question/admin/{id}`.** Saved-answer lookup repeats the original bounded search page, then (only if needed) performs one `search` request using a short prefix of the saved prompt. It accepts only an exact UUID match and unchanged prompt, images, alternative IDs, and alternative contents. Reordering alternatives does not change the selected ID. A missing or changed question pauses for review; there is no pagination crawl.

## Question-bank submission

```http
PATCH https://api.plataformaassaad.com.br/v1/question/answer
Authorization: Bearer <browser-held token>
Content-Type: application/json

{"question_id":"<question UUID>","alternative_id":"<alternative UUID>"}
```

This is the exact body assembled by the question-bank page’s `onAnswer` callback. The component uses `created.confirmed`, `created.id`, `created.alternative_id`, `created.answered_at`, top-level `correct`, `correctAnswer`, `explanation`, and `experience`. It renders grading only after `created.confirmed`.

The adapter requires `created.confirmed === true`, matching `created.alternative_id` from the already verified question, and boolean `correct`. If `created.question_id` is present, it must match too. If `correctAnswer.id` is supplied, it must belong to the saved alternatives and agree with `correct`. Missing optional fields do not invalidate an otherwise verified confirmation: a correct selection identifies itself; an incorrect selection without a supplied correct option stays confirmed without revealing or guessing another option. The complete live write response has not been captured. Missing or contradictory required confirmation fields produce **unknown**, never an automatic write retry.

**Server idempotency is not established.** A Noti command UUID identifies one dispatch; it is not an Assaad-supported idempotency header. The tablet persists `pending` before sending, the extension persists its dispatch marker, and the relay deduplicates recent commands. Transport loss, timeout, or an unverified response after PATCH starts requires user review and never causes an automatic second PATCH.

HTTP 401, 403, 429, schema differences, and lookup failures before a write block the item and pause its queue. No automatic retry loop calls the API. Login/account blocks can resume after the extension observes a valid login for the original account. Rate limits require waiting and explicit resume. If a write had started, even an HTTP error remains unknown to avoid a duplicate attempt.

## Endpoint catalogue

All REST paths below are relative to the `/v1` base except `/api/auth/*`, which belong to the website. “Documented only” means the endpoint was found in source but is not called by this implementation. Exact schemas not present in call sites are explicitly left unverified. Full request/response notes and source URLs are in [the machine-readable catalogue](plataforma-assaad-endpoints.json).

| Method | Path | Purpose | Noti integration |
| --- | --- | --- | --- |
| GET | `/api/auth/session` | Read the current website session | used; token stays in page |
| POST | `/api/auth/refresh` | Website-managed cookie refresh | website only |
| POST | `/api/auth/session` | Persist a website login | website only |
| DELETE | `/api/auth/session` | Website logout | website only |
| GET | `/question` | Published question search and pagination | used |
| GET | `/question/metadata` | Filter taxonomy | tablet read-only connection check |
| PATCH | `/question/answer` | Confirm an answer in the question bank | used |
| GET | `/question/answers-key` | PDF-export answer key | not called; export flow only |
| POST | `/question/bookmark` | Save a practice-later bookmark | documented only |
| DELETE | `/question/bookmark/{bookmarkId}` | Remove the user bookmark | documented only |
| GET | `/question/filter` | Read saved search filters | documented only |
| POST | `/question/filter` | Save search filters | documented only |
| DELETE | `/question/filter/{filterId}` | Delete saved filters | documented only |
| GET | `/question/folder` | Read question folders and membership | documented only |
| POST | `/question/folder` | Create a user folder | documented only |
| PATCH | `/question/folder/{folderId}` | Edit a user folder | documented only |
| DELETE | `/question/folder/{folderId}` | Delete a user folder | documented only |
| POST | `/question/folder/{folderId}/question/{questionId}` | Add a question to a folder | documented only |
| DELETE | `/question/folder/{folderId}/question/{questionId}` | Remove a question from a folder | documented only |
| GET | `/question/{questionId}/notes` | Read notes | documented only |
| POST | `/question/{questionId}/notes` | Create notes | documented only |
| PATCH | `/question/{questionId}/notes/{itemId}` | Edit notes | documented only |
| DELETE | `/question/{questionId}/notes/{itemId}` | Delete notes | documented only |
| GET | `/question/{questionId}/comments` | Read comments | documented only |
| POST | `/question/{questionId}/comments` | Create comments | documented only |
| PATCH | `/question/{questionId}/comments/{itemId}` | Edit comments | documented only |
| DELETE | `/question/{questionId}/comments/{itemId}` | Delete comments | documented only |
| GET | `/question/{questionId}/comments/{commentId}/replies` | Read discussion replies | documented only |
| POST | `/question/{questionId}/comments/{commentId}/upvote` | Vote on discussion | documented only |
| GET | `/stats/questions/statsfull` | Aggregate question statistics | documented only |
| GET | `/stats/user/today/questions` | Today’s question activity | documented only |
| GET | `/question/list` | Question lists | documented only |
| GET | `/question/list/metadata` | List filter taxonomy | documented only |
| GET | `/question/list/in-progress` | Unfinished lists | documented only |
| GET | `/question/list/recently-added` | Recent lists | documented only |
| GET | `/question/list/progressed/{listId}` | List questions and attempt state | documented only |
| PATCH | `/question/list/answer/set` | Save an answer within a list attempt | separate workflow; not used |
| POST | `/question/list/answer/confirm/{answerId}` | Confirm a previously set list answer | separate workflow; not used |
| PATCH | `/question/list/attempt/finish/{attemptId}` | Finish a list attempt | documented only |
| GET | `/stats/user/list/{listId}/attempts` | Read list attempt history/results | documented only |
| GET | `/stats/user/list/{listId}/attempts/byid/{attemptId}` | Read list attempt history/results | documented only |
| GET | `/stats/user/list/{listId}/attempts/latest` | Read list attempt history/results | documented only |
| GET | `/question/list/categories` | List categories | documented only |
| GET | `/question/list/subcategories` | List subcategories | documented only |
| GET | `/question/list/category/{categoryId}` | List category detail | documented only |
| GET | `/dashboard/user/new-header` | Current dashboard header | documented only |
| GET | `/dashboard/user/global` | Global dashboard and study status | documented only |
| GET | `/dashboard/user/performance` | Performance summary | documented only |
| GET | `/dashboard/user/performance/accuracy-chart` | Accuracy history | documented only |
| GET | `/dashboard/user/performance/exam-history` | Exam history | documented only |
| GET | `/dashboard/user/goals` | Study goals | documented only |
| GET | `/profile/me` | Current user profile | not called; avoid collecting profile data |
| GET | `/profile/me/statistics` | Current profile statistics | not called; avoid collecting profile data |
| GET | `/timer/focus-session/current` | Legacy current focus session | future study integration; not used |
| GET | `/timer/focus-session` | Legacy focus session history | future study integration; not used |
| GET | `/chronos/status` | Current Chronos capability/status | future study integration; not used |
| GET | `/chronos/sessions/current` | Chronos current focus session | future study integration; not used |
| GET | `/chronos/sessions` | Chronos focus history | future study integration; not used |
| GET | `/mpc/progress` | Progress map | future study integration; not used |
| GET | `/daily-progress/schedule/today` | Today’s study schedule | future study integration; not used |
| GET | `/daily-progress/schedule/range` | Schedule across a requested range | future study integration; not used |
| GET | `/daily-progress/progress` | Daily study progress | future study integration; not used |

## Distinct workflows and restricted areas

- Lists have their own attempt IDs: `PATCH /question/list/answer/set` saves a selection; `POST /question/list/answer/confirm/{answerId}` confirms it; `PATCH /question/list/attempt/finish/{attemptId}` finishes the attempt. Noti’s current adapter targets the question bank only. A list/exam selection must never be sent to the question-bank endpoint.
- `GET /question/answers-key` appears in the PDF exporter, with the ordinary search filters and an `answers` map of question UUID to label. Availability and authorization have not been tested. It is documented and deliberately not fetched by the study flow; official grading comes from submitted-answer feedback.
- The same bundles contain administrative question/list publishing, alternative editing, access administration, and user-management calls. Their presence in downloaded code does not establish permission. They were not called and are excluded from Noti’s adapter.
- Profile endpoints are documented only as possible account/status context. Noti uses a pseudonymous session account reference and does not fetch profile or ranking details.
- The client includes a Supabase dependency and other integration/provider code. Those are not evidence that direct database access is an appropriate integration API. The adapter does not use them or any embedded provider credentials.
- Focus-session, Chronos, dashboard goals, and schedule endpoints could support a future study-time integration. Their mutation contracts have not been mapped fully or implemented here. They are not required for saving and submitting questions.

## Implemented Noti flow

1. Pair the app and extension once using the relay’s six-digit code. This pairing does not replace Assaad’s own login.
2. Click **Enviar questões ao Noti**. Read rendered question UUIDs, load the corresponding ordinary API search page once, and share only those exact displayed questions.
3. Keep multiple sets on the tablet. Draft choices save immediately and remain scoped to the account, question UUID, and content revision. Drawings use the existing native canvas store. Anonymous HTTPS images are copied locally when possible; the UI shows the offline image count and allows retrying missing assets.
4. Explicitly approve one answer or **Submit saved answers**. The tablet saves an unsent queue while offline. It persists `pending` durably before each outgoing command. Only one command is outstanding.
5. When Edge and Noti are available, reuse an Assaad tab or open one background tab. The original question page is unnecessary for API-backed saved sets. Assaad login must still be valid. Edge is not launched by the relay when closed.
6. Recheck the account reference, locate and compare the saved question, then issue one answer PATCH. Return only confirmed grading fields. Pause/cancel/resume controls are available in Saved sets.
7. Restore saved sets into a renewed pairing as needed. Forgetting a set also removes its server archive entry, unless it has an unresolved submission. Neither revocation nor relay expiry deletes the device’s question library.

Local limits: 100 sets; at most 100 questions per import, 12 alternatives and 16 images per question; bounded protocol messages. Media copies are limited to 4 MiB each and 64 MiB total, with no Assaad cookies or relay credentials. Failed/protected/oversized images stay marked as needing connectivity. Queues run while Noti is foregrounded; restarting the app converts an interrupted pending item to unknown.

## Source evidence

- [auth client asset](https://app.plataformaassaad.com.br/_next/static/chunks/32692-b6fc00a600634715.js) — SHA-256 `ebb6a0a65014f9898079b22cfaedb0b411c868ce3765da88fad0e7b3e32faa90`.
- [question client asset](https://app.plataformaassaad.com.br/_next/static/chunks/94405-da7c5f52bbcd030f.js) — SHA-256 `9aa64064d270ba23c5d47cf88957df259ff6ffcbaea5046b9fe07dee5b4052dd`.
- [page client asset](https://app.plataformaassaad.com.br/_next/static/chunks/app/(app)/(questoes)/banco-de-questoes/explorar/page-2c5d94bc9a57d86b.js) — SHA-256 `5029efa56e2d383e876a55fa3c4334de04703ecdf031f4b230b52cb4f4cb92e4`.
- [extras client asset](https://app.plataformaassaad.com.br/_next/static/chunks/72533-f88dff139a01cd32.js) — SHA-256 `dae6f671cd7f67f3e69d16a73bff6b4318c67e86d6c08609d33650fa2f091d40`.
- [lists client asset](https://app.plataformaassaad.com.br/_next/static/chunks/97393-580a2faa83d18e67.js) — SHA-256 `ff43311ceec21488a9256001578271f35cfbd4beb1ed2fa730af40de43f6db7e`.
- [dashboard client asset](https://app.plataformaassaad.com.br/_next/static/chunks/439-f5df47fa0e80a032.js) — SHA-256 `52b6db28ba7bb3c88f484e1649ab8a0cd5243b093709bc89b4dd8d4c42d2e85d`.
- [profile client asset](https://app.plataformaassaad.com.br/_next/static/chunks/61105-0d2ea2f2a2a40b78.js) — SHA-256 `07f7855bdd4e21e10c087ebe6870a14959d9b08f714ebfd80eb202e96cf2e6a2`.
- [focus client asset](https://app.plataformaassaad.com.br/_next/static/chunks/62982-e203ca97486a68c8.js) — SHA-256 `c4866e7a348c6925f7f7b72337d1cafb36306401a56a855d08673cf8fa51e12c`.

The extension uses the documented [Chrome scripting API](https://developer.chrome.com/docs/extensions/reference/api/scripting) to execute one packaged function in the Assaad MAIN world. Its inputs are fixed operations, never arbitrary URLs or JavaScript. See [Chrome’s network request guidance](https://developer.chrome.com/docs/extensions/develop/concepts/network-requests) for the distinction between page-context CORS requests and extension-origin requests. The permission remains restricted to the existing Assaad host.
