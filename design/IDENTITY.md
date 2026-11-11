# Visual identity

Spotifeur's identity follows the app: Material 3 Expressive, dark first, a green brand color, the rounded Google Sans Flex already shipped in the app (`app/src/main/res/font/gflex_variable.ttf`), and depth made of stacked shapes and soft light. Every visual (logo, banner, mockups, website) uses only what is on this page.

## Typography

One family: **Google Sans Flex** (variable, axes `wght`, `wdth`, `opsz`, `ROND`), the Material 3 Expressive typeface. Web fallback: Roboto Flex, then system sans-serif.

| Role | Setting | Size (at 1x) | Tracking |
| --- | --- | --- | --- |
| Wordmark "Spotifeur" | `wght 750`, `ROND 100`, `wdth 100` | as needed | −2% |
| Display / headlines | `wght 700`, `ROND 100` | 48–64 | −2% |
| Titles | `wght 600`, `ROND 100` | 22–28 | −1% |
| Labels, buttons | `wght 500`, `ROND 100` | 14–16 | 0 |
| Body text | `wght 400`, `ROND 0` | 16–18 | 0 |
| Code | JetBrains Mono 400 | 14 | 0 |

`ROND 100` (fully rounded terminals) is the app's "Google Sans Rounded" look. Use it for anything that reads as brand or UI. Long body text uses `ROND 0`. The name is always written **Spotifeur**: capital S, nothing else capitalized, never split.

## Colors

Dark is the default. The green is a jade green, bluer than Spotify's (`#1DB954`), which is never used.

| Token | Dark | Light | Use |
| --- | --- | --- | --- |
| `background` | `#0A100D` | `#F3FAF5` | Page and artboard background |
| `surface` | `#141C17` | `#FBFFFC` | Cards, panels |
| `surface-high` | `#202A24` | `#DDEFE3` | Raised elements, phone bezels |
| `primary` (Feur green) | `#6FE3A6` | `#0F9D63` | Brand color, main button |
| `primary-strong` | `#0F9D63` | `#0A7A4C` | Logo base, filled shapes |
| `primary-bright` | `#B4F7D1` | `#6FE3A6` | Highlights, top light on shapes |
| `primary-container` | `#0B4F35` | `#C6F2DA` | Large tinted shapes |
| `primary-deep` | `#04281B` | `#0B4F35` | Shadows inside green shapes |
| `tertiary` (lavender) | `#C9BEFF` | `#6C4FF5` | One accent per composition, never dominant |
| `on-background` | `#EAF6EF` | `#0B1F15` | Main text |
| `on-surface-variant` | `#B9CABF` | `#405048` | Secondary text |
| `outline` | `#3F4E45` | `#7C8F84` | Hairlines, borders |

Rules:

- Green carries the brand. The lavender accent appears at most once per composition, in a small shape.
- Gradients only between neighbouring green tokens (`primary-deep` → `primary-strong` → `primary` → `primary-bright`), never rainbow.
- In the app, album art recolors the player (dynamic color). That is the only place other hues appear.

## Depth

Depth comes from Material 3 Expressive elevation, not from realism:

- **Stacked shapes:** two or three flat shapes overlapping, each a step lighter than the one below it.
- **Soft shadows:** large blur, low opacity, tinted with `primary-deep` on green and black on dark backgrounds, offset downward.
- **Light from the top-left:** a subtle linear gradient on each shape (lighter top-left, darker bottom-right) and an optional thin highlight on the top edge.
- No glass, no chrome, no bevel, no photoreal 3D, no grain, no neon glow.

## Shapes

Material 3 Expressive shapes, as in the app:

- **Cookie** (scalloped circle, 9 or 12 lobes): the app's shuffle button. Main brand motif.
- **Pill** and **squircle** (corner radius 28–32 at phone scale, about 22% of the shape's size for icons).
- **Wave**: the app's wavy seek bar, as a thin line only.

## Logo constraints

- Android adaptive icon: 108 × 108 dp, glyph inside the central 66 dp safe zone. It works as a circle, squircle or rounded square, and as a single-color monochrome layer (Android 13 themed icons).
- Readable at 48 px. One idea, no text.
- Not a Spotify logo variant: no three curved bars, no circle with arcs, no `#1DB954`.

## Writing on visuals

Plain description only, no slogans. The banner carries the name, "Open-source music player for Android", the sources "Spotify · YouTube Music · Local files", and a player screenshot on the right. Mockup labels name the screen ("Player", "Queue"). Story and carousel presentation text is in English.

The editable sources and reconstruction commands are listed in [README.md](README.md).
The Instagram carousel uses a dark green-to-black gradient without background motifs;
the README artwork and story keep the stacked jade shapes and small lavender accent.

## Sizes

| Asset | Size | Format |
| --- | --- | --- |
| Logo master | 1024 × 1024 | PNG with transparency + SVG |
| GitHub social preview / README banner | 1280 × 640 | PNG |
| Mockups | 2400 × 1350 each | PNG |
| Instagram story | 1080 × 1920 | PNG |
| Instagram carousel | 1080 × 1350 per slide | PNG |
