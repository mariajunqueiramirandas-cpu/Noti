# Noti Brand Bible

Noti is the lowest-latency PDF annotator for the Xiaomi Pad 7 + Focus Pen. The product is
deadly serious about latency. The brand is not. We are a fast pen with a slow sense of humor.

## Taglines (pick per context)

- Primary: **"Ink fast, live loud."**
- Empty library: "Nothing here yet. Nib is bored."
- Trash: "Nib ate your notebooks. (They're recoverable.)"
- Save failed: "Nib dropped the ink. Retrying…"
- Export: "Beam me up, PDF."

## Mascot: Nib

Nib is a chubby, slightly cross-eyed ballpoint pen who lives in your library screen and
judges your handwriting silently. He has no arms, no legs, and no chill.

- **Personality**: goofy, clingy, unbothered. Speaks in short punchy lines. Never apologizes.
- **Voice**: lowercase-ish, playful, never corporate. Exclamation points allowed, encouraged.
- **Never**: use Nib to scold the user, mock their drawing, or explain latency budgets.

### Poses

| Asset | Pose | Where it lives |
|---|---|---|
| `brand/nib.jpg` / `nib.svg` | Classic grin | Library empty state (SVG in-app), README, PRs |
| `brand/nib-sleep.jpg` / `nib-sleep.svg` | Eyes closed, "z z" | Trash empty state (he naps on deleted stuff) |
| `brand/nib-excited.jpg` / `nib-excited.svg` | Wide eyes, arms up | New notebook created / export finished |
| `brand/banner.jpg` | Nib over doodled notebooks | Store listing / social / README hero |

The `.jpg` files are AI-generated raster art (light-gray backdrop, not transparent);
the `.svg` files are the hand-authored transparent versions used inside the app.

The in-app drawable is `app/src/main/res/drawable/mascot_nib.xml` (vector, themed-safe), and
the launcher icon is the same character on a periwinkle squircle
(`res/mipmap-anydpi-v26/ic_launcher.xml`).

## Palette

The app chrome stays pearl/slate (see `ui/theme/Theme.kt`). Brand moments use one accent:

| Name | Hex | Use |
|---|---|---|
| Nib Blue | `#2D5BD3` | Primary accent, mascot body, launcher background |
| Nib Deep | `#0F2A73` | Pen tip, outlines, monochrome icon |
| Blush | `#FF9EBB` | Cheeks, highlights, celebratory moments |
| Paper | `#F8FAFE` | Light backgrounds behind the mascot |

Dark mode keeps the same accent family (see `Theme.kt` dark scheme); the mascot is
color-stable across themes.

## Copy rules

1. Headlines can be silly; buttons stay literal ("New notebook", "Import PDF").
2. Error messages get one joke, then the fix. Never two jokes.
3. Never use the word "utilize".
4. If a screen feels empty, add Nib. If Nib doesn't fit, add a pun.

## Asset locations

- Brand doc: `docs/BRAND.md` (this file)
- Mascot artwork (SVG): `brand/`
- In-app vector mascot: `app/src/main/res/drawable/mascot_nib.xml`
- Launcher icon: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` +
  `res/drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`,
  `res/drawable/ic_launcher_background.xml`, `res/values/colors.xml`
