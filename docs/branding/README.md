# Noti — little ideas welcome.

<img src="assets/noti-wordmark.svg" alt="noti." width="180">

[Download the complete brand kit](Noti-brand-kit.zip) · [Read the PDF brand guide](Noti-brand-guide.pdf)

<img src="../../app/src/main/res/drawable-nodpi/doti.png" alt="Doti, a cheerful lavender ink companion" width="220">

Noti is a friendly home for passing thoughts, happy scribbles and notes worth keeping. Its personality is playful, warm and quietly encouraging.

Doti is an original lavender ink companion with a curled tuft, peach cheeks, a butter-yellow pencil and one little sparkle. Doti welcomes people into the library, appears in empty states and About, and identifies the app in the launcher. The full illustration was created with image generation for this identity; the compact Android vector mark is a separate small-size interpretation. Neither illustration depicts a real character or an external brand.

| Role | Color | Hex |
| --- | --- | --- |
| Desk | Soft greige background | `#F3F1EE` |
| Sheet | Content surface resting on the desk | `#FBFAF8` |
| Ink | Aubergine charcoal | `#2A2830` |
| Primary | Plum, shared with Doti | `#5C4A87` |
| Honey | Favorite bookmarks only | `#E3AE3F` |
| Covers | Lilac, butter, mint, peach, sky spines | `#D9CFEE` `#F1DFA6` `#CFE1D3` `#F2C6B8` `#CBDCEB` |

Use Ink for primary light-theme text and plum for important actions. Pastels are backgrounds and illustration colors. The app supplies corresponding dark surfaces and light text in dark mode. Selection uses a fill or outline as well as color.

Nunito is the UI and wordmark typeface, with actual static 400, 500, 600, 700 and 800 weights instantiated from the open-source variable font. Body text uses 400; labels and titles use 600; headings use 700 with slightly tightened tracking; only the wordmark uses 800. The source license is in `Nunito-OFL.txt` and bundled in the APK. The app respects Android’s text scaling and does not rely on a font download.

Use the lowercase `noti.` wordmark with a plum dot. The official product name remains Noti. Keep at least the width of the dot clear around the mark. Use the compact launcher mark at small sizes and the full transparent mascot for larger illustrations; avoid squeezing Doti or using a detailed character where a control icon belongs.

The tone is light and encouraging: “Little ideas welcome,” “Make your mark,” and “Your kind of paper.” Buttons, destructive actions, errors and hardware settings retain clear, literal labels. Never make deleting or saving sound like a joke.

The design is a page on a desk at dusk. Navigation sits directly on the greige desk; content rests on a lighter sheet with 28 dp corners. Radius follows hierarchy: 12 dp controls, 16 dp cards, 22 dp panels, 28 dp sheets and dialogs, and fully rounded pills for actions, chips and floating editor chrome. Space separates sections instead of divider lines; hairlines remain only between rows inside a grouped card. Buttons have at least 48 dp targets, with plum primary actions, outlined secondary actions, neutral quiet actions and explicit destructive styling. Selected chips fill with ink. Dialogs share an explicit-width frame with a scrolling body and fixed title and actions. Notebook cards show the real first page with a colored spine and, for favorites, a honey ribbon; they never invent content. Floating editor chrome uses a soft low shadow with transparent room in each popup window so it is not clipped. The radial dial is the one bold element: ten rounded segments, a plum active tool and a paper hub that previews the stroke.

## Sources

- `app/src/main/res/drawable-nodpi/doti.png`: full transparent mascot.
- `app/src/main/res/drawable/ic_doti_foreground.xml`: Android launcher foreground.
- `app/src/main/res/drawable/ic_doti_monochrome.xml`: themed launcher mark.
- `assets/noti-wordmark.svg`: portable outlined wordmark.
- `assets/noti-app-icon.svg`: vector icon preview.
- `badges/`: README badges and palette card, with text outlined from Nunito. Regenerate with `python3 docs/branding/badges/generate.py` (needs `fonttools`).
- `app/src/main/kotlin/app/noti/ui/common/Controls.kt`: shared action, menu and dialog styles.
- `app/src/main/kotlin/app/noti/ui/brand/Brand.kt`: shared brand components.
- `app/src/main/kotlin/app/noti/ui/theme/Theme.kt`: colors, typography and shape roles.

App screenshots in this directory are rendered from the actual Compose screens on Android 35 with Robolectric/native Skia. They capture UI appearance, not physical-device ink latency. Recreate them with `./gradlew :app:testDebugUnitTest`; `app/build/reports/brand-ui/` contains the new tablet, dark-mode, empty-state, settings, About and launcher captures. The existing mobile/editor reports cover phones, large text and the radial controls.
