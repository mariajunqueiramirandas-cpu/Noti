#!/usr/bin/env python3
"""Generate the README's badges and palette card as self-contained SVGs.

Text is converted to outlines from the bundled Nunito fonts, so the files need
no web fonts and no third-party badge service. Regenerate from the repo root:

    pip install fonttools
    python3 docs/branding/badges/generate.py
"""
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
FONTS = ROOT / "app/src/main/res/font"

INK, SHEET, DESK, PLUM = "#2A2830", "#FBFAF8", "#F3F1EE", "#5C4A87"
MUTED, HAIRLINE = "#6C6873", "#E3DFE2"
LILAC, BUTTER, MINT, PEACH, SKY = "#D9CFEE", "#F1DFA6", "#CFE1D3", "#F2C6B8", "#CBDCEB"
HONEY = "#E3AE3F"

_fonts: dict[str, TTFont] = {}


def font(weight: str) -> TTFont:
    if weight not in _fonts:
        _fonts[weight] = TTFont(FONTS / f"nunito_{weight}.ttf")
    return _fonts[weight]


def text(s: str, x: float, baseline: float, size: float, weight: str, fill: str) -> tuple[str, float]:
    """Return (svg <path>, advance width) for s drawn with its baseline at y=baseline."""
    f = font(weight)
    glyphs, cmap, hmtx = f.getGlyphSet(), f.getBestCmap(), f["hmtx"]
    scale = size / f["head"].unitsPerEm
    pen = SVGPathPen(glyphs, ntos=lambda v: f"{v:.2f}".rstrip("0").rstrip("."))
    cursor = x
    for ch in s:
        name = cmap.get(ord(ch), ".notdef")
        glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, cursor, baseline)))
        cursor += hmtx[name][0] * scale
    return f'<path fill="{fill}" d="{pen.getCommands()}"/>', cursor - x


def width(s: str, size: float, weight: str) -> float:
    return text(s, 0, 0, size, weight, "#000")[1]


def on(color: str) -> str:
    """Ink or sheet text, whichever reads better on color."""
    r, g, b = (int(color[i:i + 2], 16) for i in (1, 3, 5))
    return INK if 0.299 * r + 0.587 * g + 0.114 * b > 140 else SHEET


def svg(w: float, h: float, label: str, body: str) -> str:
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w:.0f}" height="{h:.0f}" '
            f'viewBox="0 0 {w:.0f} {h:.0f}" role="img" aria-label="{label}"><title>{label}</title>{body}</svg>\n')


def badge(name: str, label: str, value: str, color: str) -> None:
    h, size, pad, r = 28, 13, 12, 14
    lw = width(label, size, "semibold") + 2 * pad
    vw = width(value, size, "extrabold") + 2 * pad
    w = lw + vw
    base = h / 2 + size * 0.36
    label_path, _ = text(label, pad, base, size, "semibold", SHEET)
    value_path, _ = text(value, lw + pad, base, size, "extrabold", on(color))
    body = (f'<clipPath id="c"><rect width="{w:.1f}" height="{h}" rx="{r}"/></clipPath>'
            f'<g clip-path="url(#c)"><rect width="{w:.1f}" height="{h}" fill="{INK}"/>'
            f'<rect x="{lw:.1f}" width="{vw:.1f}" height="{h}" fill="{color}"/></g>'
            f'{label_path}{value_path}')
    (OUT / f"{name}.svg").write_text(svg(w, h, f"{label}: {value}", body))


def download_button() -> None:
    h, size, r = 48, 17, 24
    caption = "Download the latest APK"
    icon_w, gap, pad = 16, 10, 24
    w = pad + icon_w + gap + width(caption, size, "extrabold") + pad
    cx, top = pad + icon_w / 2, h / 2 - 9
    arrow = (f'<path d="M{cx} {top}v12M{cx - 6} {top + 7}l6 6 6-6M{pad} {top + 18}h{icon_w}" '
             f'fill="none" stroke="{SHEET}" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>')
    label, _ = text(caption, pad + icon_w + gap, h / 2 + size * 0.36, size, "extrabold", SHEET)
    body = f'<rect width="{w:.1f}" height="{h}" rx="{r}" fill="{PLUM}"/>{arrow}{label}'
    (OUT / "download.svg").write_text(svg(w, h, caption, body))


def palette() -> None:
    rows = [
        [("Desk", "#F3F1EE"), ("Sheet", "#FBFAF8"), ("Ink", INK), ("Plum", PLUM), ("Honey", HONEY)],
        [("Lilac", LILAC), ("Butter", BUTTER), ("Mint", MINT), ("Peach", PEACH), ("Sky", SKY)],
    ]
    pad, sw, sh, gap, row_h = 28, 132, 64, 16, 124
    w = 2 * pad + 5 * sw + 4 * gap
    h = 2 * pad + len(rows) * row_h - 12
    parts = [f'<rect x="1" y="1" width="{w - 2}" height="{h - 2}" rx="28" fill="{SHEET}" stroke="{HAIRLINE}" stroke-width="2"/>']
    for ri, row in enumerate(rows):
        y = pad + ri * row_h
        for ci, (name, hex_) in enumerate(row):
            x = pad + ci * (sw + gap)
            stroke = f' stroke="{HAIRLINE}" stroke-width="2"' if hex_ in ("#F3F1EE", "#FBFAF8") else ""
            parts.append(f'<rect x="{x}" y="{y}" width="{sw}" height="{sh}" rx="16" fill="{hex_}"{stroke}/>')
            parts.append(text(name, x + 2, y + sh + 22, 15, "bold", INK)[0])
            parts.append(text(hex_, x + 2, y + sh + 40, 12, "semibold", MUTED)[0])
    (OUT / "palette.svg").write_text(svg(w, h, "Noti palette", "".join(parts)))


if __name__ == "__main__":
    badge("android", "Android", "13+", MINT)
    badge("arm64", "arm64", "only", SKY)
    badge("kotlin", "Kotlin", "Compose M3", PLUM)
    badge("cpp", "C++20", "ink engine", PLUM)
    badge("font", "font", "Nunito", LILAC)
    badge("vibes", "vibes", "page on a desk at dusk", BUTTER)
    badge("palms", "palms", "rejected", PEACH)
    download_button()
    palette()
