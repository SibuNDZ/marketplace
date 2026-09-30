# eRestyu brand pack

Start with **brand-sheet.png**: it shows the logo, every colour with its hex
value, the typefaces and the usage rules on one page.

## Which file to use

| You need | Use |
|---|---|
| Profile picture (Facebook, TikTok, Instagram, X, WhatsApp) | `social/avatar-1080.png` |
| Logo on a light background | `png/logo/erestyu-logo-*.png` (transparent) or `*-on-paper.png` |
| Logo on a dark background or a photo | `png/logo/erestyu-logo-white-*.png` |
| Logo in one colour, e.g. a print job that cannot do gradients | `png/logo/erestyu-logo-ink-*.png` |
| Just the "e" icon | `png/mark/erestyu-mark-*.png` |
| The "e" icon on a photo or a flame background | `png/mark/erestyu-mark-white-*.png` |
| Anything that will be scaled, printed or edited | the matching file in `svg/` |

PNG sizes are pixel widths: 600 for social posts, 1200 for most web use, 2400
for print or large banners. Every logo PNG already includes the clear space
described below.

All SVGs have their letters converted to shapes, so they look identical on any
computer without installing a font.

## Regenerating

`tools/` rebuilds everything here, and the site's favicon and app icons, from
one geometry: `cd tools && npm install && node build.js`. The site's header
logo (LogoMark.tsx) uses the same shapes. Edit the generator, never the files.

## Colours

| Name | Hex | Use | Text on it |
|---|---|---|---|
| Flame gradient | `#FF9A3D` > `#FF4626` (60%) > `#E31C3D` | **Logo only**, top-left to bottom-right | |
| Flame | `#E2582F` | Main accent: buttons, highlights | Ink, or white at large sizes only |
| Flame deep | `#B84424` | Accent where text sits on it | White |
| Sun | `#E89B4E` | Secondary accent, highlights | Ink |
| Aloe | `#2E6B4F` | Green accent: success, "in stock" | White |
| Ink | `#1F1D1B` | Text | White |
| Ink soft | `#6B6763` | Secondary text | White |
| Paper | `#FAF9F7` | Background | Ink |

"Text on it" follows the WCAG accessibility contrast guideline of 4.5:1 for
normal text. White on Flame is 3.7:1, which is fine for large headings but too
faint for small text; use Flame deep or Ink text instead.

## Typefaces

- **Space Grotesk** for headings. The wordmark is Space Grotesk Bold.
- **DM Sans** for body text.

Both are free on Google Fonts under the SIL Open Font License, so they can be
used in any design tool and for any purpose.

The "e" in the icon is Georgia Bold. It is supplied as a shape in every logo
file, so you never need the font to use the logo; just do not retype it.

## Using the logo

- Leave clear space around the logo of at least half the height of the "e" tile.
- On photos or flame backgrounds, use the white versions.
- Do not recolour, stretch, rotate, outline or add effects to the logo.
- The gradient is for the logo. Use the solid colours for everything else.
- For profile pictures, use `social/avatar-1080.png`. The square is filled edge
  to edge, so the circle crop every platform applies never clips a corner.
