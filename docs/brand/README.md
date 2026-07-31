# Kmemo brand assets

The logo is the whole name: the node **K** followed by **memo**. The K on its own is a tile, not the
logo, and "memo" is never separated from it.

| File | Use |
| --- | --- |
| `kmemo-lockup-dark.svg` | the logo on a dark surface |
| `kmemo-lockup-light.svg` | the logo on a light surface |
| `kmemo-lockup-mono-dark.svg` / `-mono-light.svg` | one flat ink |
| `kmemo-symbol.svg` / `kmemo-symbol-mono.svg` | the K on its own, internal use |
| `kmemo-favicon.svg` / `-light.svg` / `kmemo-favicon-512.png` | favicon, avatar, app icon |
| `kmemo-social-preview.png` | the GitHub social preview (2560×1280) |
| `kmemo-tokens.css` | colour, type, space, radius and elevation tokens |
| [`../kmemo-hero.png`](../kmemo-hero.png) | the README banner (2560×680) |

Every SVG here is self-contained. The wordmark is outlined, so it renders identically whether or not
Space Grotesk is installed.

**Construction.** A 1000 em grid with a cap height of 700. The stem is 115 (Space Grotesk 600's stem
weight, so the mark and the letters carry the same black); the diagonals are 105, 9% lighter, because at
equal weight a diagonal reads heavier. Terminals are round (r 82) and overshoot cap line and baseline by
10; the vertex sits at 55% of the height, not halfway. K/m kerns `-0.012em`, the wordmark tracks
`-0.032em`. Clear space is the x-height. Minimum size is 15px of body. Below that, only the K in its disc.

**One accent.** Amber on the high node. The low node is a terminal, not a second signal.

**Type.** Space Grotesk 600 (headings and wordmark), IBM Plex Sans (body), JetBrains Mono (code,
metrics, labels).

**Colour.** Ink `#05070E` → `#16203A`, borders `#1E2A45` / `#22314F`, text `#F2F6FF` down to `#6B7A96`.
Blue `#5B9CFF` is primary, amber `#F5B54A` is the single accent, and Kotlin purple `#7F52FF` is reserved
for platform references.

**Don't.** Separate "memo" from the K · make the low node amber · add gradients, shadows or outlines ·
swap the typeface or alter the tracking · rotate, skew or stretch the K.
