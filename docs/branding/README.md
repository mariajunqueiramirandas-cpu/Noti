# Noti — little ideas welcome.

<img src="assets/noti-wordmark.svg" alt="noti." width="180">

[Download the complete brand kit](Noti-brand-kit.zip) · [Read the PDF brand guide](Noti-brand-guide.pdf)

<img src="../../app/src/main/res/drawable-nodpi/doti.png" alt="Doti, a cheerful lavender ink companion" width="220">

Noti is a friendly home for passing thoughts, happy scribbles and notes worth keeping. Its personality is playful, warm and quietly encouraging.

Doti is an original lavender ink companion with a curled tuft, peach cheeks, a butter-yellow pencil and one little sparkle. Doti welcomes people into the library, appears in empty states and About, and identifies the app in the launcher. The full illustration was created with image generation for this identity; the compact Android vector mark is a separate small-size interpretation. Neither illustration depicts a real character or an external brand.

| Role | Color | Hex |
| --- | --- | --- |
| Paper | Warm off-white | `#FAF9F6` |
| Ink | Soft charcoal | `#30332E` |
| Primary | Accessible plum | `#6D568F` |
| Lilac | Doti and welcome accents | `#DED2F4` |
| Butter | Pencil and notebook covers | `#F5D77E` |
| Peach | Cheeks and notebook covers | `#F6B6A6` |
| Mint | Gentle supporting accents | `#DDEBDD` |

Use Ink for primary light-theme text and plum for important actions. Pastels are backgrounds and illustration colors. The app supplies corresponding dark surfaces and light text in dark mode. Selection uses a fill or outline as well as color.

Nunito is the UI and wordmark typeface, with actual static 400, 500, 600, 700 and 800 weights instantiated from the open-source variable font. Body text uses 400; labels use 600–700; welcome headings and the wordmark use 700–800. The source license is in `Nunito-OFL.txt` and bundled in the APK. The app respects Android’s text scaling and does not rely on a font download.

Use the lowercase `noti.` wordmark with a plum dot. The official product name remains Noti. Keep at least the width of the dot clear around the mark. Use the compact launcher mark at small sizes and the full transparent mascot for larger illustrations; avoid squeezing Doti or using a detailed character where a control icon belongs.

The tone is light and encouraging: “Little ideas welcome,” “Make your mark,” and “Your kind of paper.” Buttons, destructive actions, errors and hardware settings retain clear, literal labels. Never make deleting or saving sound like a joke.

The design uses fine dividers, flat surfaces, restrained 10–20 dp corners and generous spacing. Notebook covers add color without inventing document content. The radial editor remains mostly monochrome; the home mark is its only mascot detail. Mascot images beside readable copy are decorative for accessibility.

## Sources

- `app/src/main/res/drawable-nodpi/doti.png`: full transparent mascot.
- `app/src/main/res/drawable/ic_doti_foreground.xml`: Android launcher foreground.
- `app/src/main/res/drawable/ic_doti_monochrome.xml`: themed launcher mark.
- `assets/noti-wordmark.svg`: portable outlined wordmark.
- `assets/noti-app-icon.svg`: vector icon preview.
- `app/src/main/kotlin/app/noti/ui/brand/Brand.kt`: shared brand components.
- `app/src/main/kotlin/app/noti/ui/theme/Theme.kt`: colors, typography and shape roles.

App screenshots in this directory are rendered from the actual Compose screens on Android 35 with Robolectric/native Skia. They capture UI appearance, not physical-device ink latency. Recreate them with `./gradlew :app:testDebugUnitTest`; `app/build/reports/brand-ui/` contains the new tablet, dark-mode, empty-state, settings, About and launcher captures. The existing mobile/editor reports cover phones, large text and the radial controls.
