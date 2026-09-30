// eRestyu brand pack generator. Every glyph is outlined to a path, so no
// output depends on a font being installed where it is opened.
//
// Run from this folder:  npm install && node build.js
// Writes the pack into brand/ and the site icons into
// marketplace-frontend/public/ (favicon.svg, favicon.ico, apple-touch-icon.png,
// icon-512.png). LogoMark.tsx carries the same tile and "e" paths; if the
// geometry ever changes, copy them from _site/geometry.json into it.
//
// The "e" is Georgia Bold, traced once by extract-e.js at the exact size and
// position the site icon has always used, and stored in serif-e.json. Nothing
// here reads Georgia, so this runs anywhere, not only on a Windows machine.
const fs = require('fs')
const path = require('path')
const opentype = require('opentype.js')
const { Resvg } = require('@resvg/resvg-js')

const ROOT = path.resolve(__dirname, '..', '..')
const FONTS = path.join(ROOT, 'marketplace-frontend', 'node_modules', '@fontsource')
const OUT = path.resolve(__dirname, '..')
const PUBLIC = path.join(ROOT, 'marketplace-frontend', 'public')
const SERIF_E = JSON.parse(fs.readFileSync(path.join(__dirname, 'serif-e.json'), 'utf8'))
const grotesk = opentype.loadSync(`${FONTS}/space-grotesk/files/space-grotesk-latin-700-normal.woff`)
const grotesk5 = opentype.loadSync(`${FONTS}/space-grotesk/files/space-grotesk-latin-500-normal.woff`)
const sans = opentype.loadSync(`${FONTS}/dm-sans/files/dm-sans-latin-400-normal.woff`)
const sansBold = opentype.loadSync(`${FONTS}/dm-sans/files/dm-sans-latin-700-normal.woff`)

// Warm flame, as used by the logo tile in both themes (LogoMark.tsx,
// favicon.svg). Solid colours are the site's light-theme tokens (tokens.css).
const GRAD = [['0%', '#FF9A3D'], ['60%', '#FF4626'], ['100%', '#E31C3D']]
const C = {
  paper: '#FAF9F7', ink: '#1F1D1B', inkSoft: '#6B6763', white: '#FFFFFF',
  flame: '#E2582F', flameDeep: '#B84424', sun: '#E89B4E', aloe: '#2E6B4F',
}
// The icon's own "e" colour, unchanged from favicon.svg.
const E_FILL = '#F5F7F3'

const gradDef = (id, units = 'objectBoundingBox', coords = 'x1="0" y1="0" x2="1" y2="1"') =>
  `<linearGradient id="${id}" gradientUnits="${units}" ${coords}>` +
  GRAD.map(([o, c]) => `<stop offset="${o}" stop-color="${c}"/>`).join('') + '</linearGradient>'

const r = n => Math.round(n * 1000) / 1000

// ---- the "e" in the tile, centred on its own bounding box -----------------
// Tile geometry matches the live mark: 24-unit square, 5.28 corner radius
// (22%), here at 512 units for precision.
const T = 512, RX = r(T * 5.28 / 24)
// The traced serif "e", already positioned inside the 512-unit tile exactly
// as favicon.svg draws it (Georgia Bold, 16.5 of 24 units, centred, baseline
// at 17.3). See extract-e.js.
const eInTile = SERIF_E.path

// The same outline at another size and centre, for full-bleed art such as the
// social avatar: scaled so its ink is heightRatio of the square, centred.
function eScaledInto(size, heightRatio) {
  const b = SERIF_E.bbox
  const s = (size * heightRatio) / (b.y2 - b.y1)
  const tx = size / 2 - s * (b.x1 + b.x2) / 2
  const ty = size / 2 - s * (b.y1 + b.y2) / 2
  return `<g transform="translate(${r(tx)} ${r(ty)}) scale(${r(s)})"><path d="${eInTile}" fill="${E_FILL}"/></g>`
}
const tileRect = `M${RX} 0H${T - RX}A${RX} ${RX} 0 0 1 ${T} ${RX}V${T - RX}A${RX} ${RX} 0 0 1 ${T - RX} ${T}H${RX}A${RX} ${RX} 0 0 1 0 ${T - RX}V${RX}A${RX} ${RX} 0 0 1 ${RX} 0Z`

const markInner = (id) =>
  `<defs>${gradDef(id)}</defs><path d="${tileRect}" fill="url(#${id})"/><path d="${eInTile}" fill="${E_FILL}"/>`
const markSvg = () =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${T} ${T}">${markInner('flame')}</svg>`
// White tile with the e knocked out (evenodd), for photos and flame backgrounds.
const markWhiteSvg = () =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${T} ${T}"><path d="${tileRect} ${eInTile}" fill="${C.white}" fill-rule="evenodd"/></svg>`

// ---- wordmark ---------------------------------------------------------------
const WORD = 'eRestyu'
const WM_SIZE = 200
const wmPath = grotesk.getPath(WORD, 0, 0, WM_SIZE)
const wmBB = wmPath.getBoundingBox()
const wmW = wmBB.x2 - wmBB.x1, wmH = wmBB.y2 - wmBB.y1
const wmD = grotesk.getPath(WORD, -wmBB.x1, -wmBB.y1, WM_SIZE).toPathData(3)
// Cap height, for aligning the wordmark to the tile in the lockup.
const capBB = grotesk.getPath('R', 0, 0, WM_SIZE).getBoundingBox()
const capH = capBB.y2 - capBB.y1
const baselineInWm = -wmBB.y1 // baseline y inside the wordmark box

function wordmarkSvg(fill) {
  const defs = fill === 'gradient' ? `<defs>${gradDef('wm')}</defs>` : ''
  const f = fill === 'gradient' ? 'url(#wm)' : fill
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${r(wmW)} ${r(wmH)}">${defs}<path d="${wmD}" fill="${f}"/></svg>`
}

// ---- horizontal lockup: tile + wordmark ----------------------------------
// Proportions from the live header (26px tile beside 25px wordmark), rounded
// to clean ratios: cap height = 62% of tile height, gap = 28% of tile.
function logoSvg(wordFill, pad = 0) {
  const H = T
  const s = (H * 0.62) / capH // wordmark scale
  const gap = H * 0.28
  const wX = H + gap
  const baseline = H / 2 + (capH * s) / 2 // cap height centred on the tile
  const wY = baseline - baselineInWm * s
  const W = wX + wmW * s
  // userSpaceOnUse resolves in the coordinate system of the element that
  // uses it, which is INSIDE the scaled group below. So the gradient runs
  // across the wordmark's own unscaled box, not the outer lockup.
  const defs = gradDef('flame') + (wordFill === 'gradient'
    ? gradDef('wm', 'userSpaceOnUse', `x1="0" y1="0" x2="${r(wmW)}" y2="${r(wmH)}"`) : '')
  const f = wordFill === 'gradient' ? 'url(#wm)' : wordFill
  const vbW = W + pad * 2, vbH = H + pad * 2
  return {
    w: vbW, h: vbH,
    svg: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${-pad} ${-pad} ${r(vbW)} ${r(vbH)}"><defs>${defs}</defs>` +
      `<path d="${tileRect}" fill="url(#flame)"/><path d="${eInTile}" fill="${E_FILL}"/>` +
      `<g transform="translate(${r(wX)} ${r(wY)}) scale(${r(s)})"><path d="${wmD}" fill="${f}"/></g></svg>`,
  }
}

// ---- social avatar: full-bleed, so a circle crop never clips a corner ------
function avatarSvg() {
  const S = 1080
  // Sized to sit well inside the circle every platform crops to.
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${S} ${S}"><defs>${gradDef('flame')}</defs>` +
    `<rect width="${S}" height="${S}" fill="url(#flame)"/>${eScaledInto(S, 0.42)}</svg>`
}

// ---- contrast (WCAG 2.x) ----------------------------------------------------
function lum(hex) {
  const [R, G, B] = [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map(v => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4))
  return 0.2126 * R + 0.7152 * G + 0.0722 * B
}
const contrast = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((m, n) => n - m); return (x + 0.05) / (y + 0.05) }

// ---- brand sheet ------------------------------------------------------------
function text(font, str, x, y, size, fill) {
  return `<path d="${font.getPath(str, x, y, size).toPathData(2)}" fill="${fill}"/>`
}
function brandSheetSvg() {
  const W = 1600, M = 96
  let y = M, out = ''
  const logo = logoSvg('gradient')
  const lw = 620, ls = lw / logo.w
  out += `<g transform="translate(${M} ${y}) scale(${r(ls)})">${logo.svg.replace(/^<svg[^>]*>|<\/svg>$/g, '')}</g>`
  y += logo.h * ls + 70
  out += text(grotesk, 'Brand colours', M, y, 44, C.ink); y += 40
  out += text(sans, 'The logo gradient, and the solid colours the site uses. Hex values are exact.', M, y + 16, 24, C.inkSoft); y += 60

  // gradient swatch
  out += `<defs>${gradDef('sheetGrad', 'objectBoundingBox', 'x1="0" y1="0" x2="1" y2="0"')}</defs>`
  out += `<rect x="${M}" y="${y}" width="${W - 2 * M}" height="130" rx="18" fill="url(#sheetGrad)"/>`
  out += text(sansBold, 'Flame gradient (logo only)', M, y + 172, 26, C.ink)
  out += text(sans, '#FF9A3D  >  #FF4626 (60%)  >  #E31C3D, top-left to bottom-right', M, y + 208, 24, C.inkSoft)
  y += 260

  const sw = [
    ['Flame', C.flame, 'Main accent: buttons, highlights'],
    ['Flame deep', C.flameDeep, 'Accent for small text and buttons'],
    ['Sun', C.sun, 'Secondary accent, highlights'],
    ['Aloe', C.aloe, 'Green accent: success, "in stock"'],
    ['Ink', C.ink, 'Text'],
    ['Ink soft', C.inkSoft, 'Secondary text'],
    ['Paper', C.paper, 'Background'],
  ]
  const cols = 4, gapX = 32, cw = (W - 2 * M - gapX * (cols - 1)) / cols, ch = 150
  sw.forEach(([name, hex, use], i) => {
    const cx = M + (i % cols) * (cw + gapX), cy = y + Math.floor(i / cols) * (ch + 150)
    out += `<rect x="${r(cx)}" y="${cy}" width="${r(cw)}" height="${ch}" rx="16" fill="${hex}" stroke="#E4E1DC" stroke-width="2"/>`
    const onW = contrast(hex, C.white), onI = contrast(hex, C.ink)
    const best = onW >= onI ? C.white : C.ink
    out += text(sansBold, name, cx + 22, cy + ch - 26, 26, best)
    out += text(sansBold, hex, cx, cy + ch + 38, 24, C.ink)
    out += text(sans, use, cx, cy + ch + 70, 20, C.inkSoft)
    out += text(sans, `White text ${onW.toFixed(1)}:1  Ink text ${onI.toFixed(1)}:1`, cx, cy + ch + 100, 18, C.inkSoft)
  })
  y += 2 * (ch + 150) + 20

  out += text(grotesk, 'Typefaces', M, y, 44, C.ink); y += 64
  out += text(grotesk, 'Space Grotesk', M, y, 52, C.ink)
  out += text(sans, 'Headings and the wordmark. Free on Google Fonts.', M + 430, y - 8, 24, C.inkSoft); y += 70
  out += text(sans, 'DM Sans', M, y, 52, C.ink)
  out += text(sans, 'Body text. Free on Google Fonts.', M + 430, y - 8, 24, C.inkSoft); y += 90

  out += text(grotesk, 'Using the logo', M, y, 44, C.ink); y += 56
  const rules = [
    'Leave clear space around the logo of at least half the height of the "e" tile.',
    'On photos or flame backgrounds, use the white versions.',
    'Do not recolour, stretch, rotate, outline or add effects to the logo.',
    'The gradient is for the logo. Use the solid colours for everything else.',
    'For profile pictures, use social/avatar-1080.png. It survives circle cropping.',
  ]
  rules.forEach(t => { out += text(sans, `•  ${t}`, M, y, 24, C.ink); y += 42 })
  y += M - 42
  return { w: W, h: Math.ceil(y), svg: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${Math.ceil(y)}"><rect width="${W}" height="${Math.ceil(y)}" fill="${C.paper}"/>${out}</svg>` }
}

// ---- write ------------------------------------------------------------------
const w = (rel, data) => { const p = path.join(OUT, rel); fs.mkdirSync(path.dirname(p), { recursive: true }); fs.writeFileSync(p, data) }
const png = (svg, width, bg) => new Resvg(svg, { fitTo: { mode: 'width', value: width }, ...(bg ? { background: bg } : {}) }).render().asPng()

const svgs = {
  'svg/erestyu-mark.svg': markSvg(),
  'svg/erestyu-mark-white.svg': markWhiteSvg(),
  'svg/erestyu-wordmark.svg': wordmarkSvg('gradient'),
  'svg/erestyu-wordmark-ink.svg': wordmarkSvg(C.ink),
  'svg/erestyu-wordmark-white.svg': wordmarkSvg(C.white),
  'svg/erestyu-logo.svg': logoSvg('gradient').svg,
  'svg/erestyu-logo-ink.svg': logoSvg(C.ink).svg,
  'svg/erestyu-logo-white.svg': logoSvg(C.white).svg,
}
for (const [k, v] of Object.entries(svgs)) w(k, v)

for (const s of [64, 180, 512, 1024]) {
  w(`png/mark/erestyu-mark-${s}.png`, png(markSvg(), s))
  w(`png/mark/erestyu-mark-white-${s}.png`, png(markWhiteSvg(), s))
}
// Lockups get breathing room (the clear-space rule) in the PNGs.
const PAD = T * 0.25
for (const width of [600, 1200, 2400]) {
  w(`png/logo/erestyu-logo-${width}.png`, png(logoSvg('gradient', PAD).svg, width))
  w(`png/logo/erestyu-logo-${width}-on-paper.png`, png(logoSvg('gradient', PAD).svg, width, C.paper))
  w(`png/logo/erestyu-logo-ink-${width}.png`, png(logoSvg(C.ink, PAD).svg, width))
  w(`png/logo/erestyu-logo-white-${width}.png`, png(logoSvg(C.white, PAD).svg, width))
  w(`png/logo/erestyu-logo-white-${width}-on-ink.png`, png(logoSvg(C.white, PAD).svg, width, C.ink))
}
w('social/avatar-1080.png', png(avatarSvg(), 1080))
const sheet = brandSheetSvg()
w('brand-sheet.png', png(sheet.svg, 1600))

// The site's own icons, from this same geometry, so the site and the pack
// can never drift. Written straight into the frontend's public/ folder.
const site = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${T} ${T}">${markInner('flame')}</svg>`
fs.writeFileSync(path.join(PUBLIC, 'favicon.svg'), site)
fs.writeFileSync(path.join(PUBLIC, 'apple-touch-icon.png'), png(site, 180))
fs.writeFileSync(path.join(PUBLIC, 'icon-512.png'), png(site, 512))
w('_site/geometry.json', JSON.stringify({ viewBox: T, rx: RX, tilePath: tileRect, ePath: eInTile, eFill: E_FILL }, null, 2))
const toIco = require('png-to-ico')
;(toIco.default || toIco)([16, 32, 48].map(sz => png(site, sz)))
  .then(buf => fs.writeFileSync(path.join(PUBLIC, 'favicon.ico'), buf))

console.log(JSON.stringify({
  wordmark: { w: r(wmW), h: r(wmH) },
  contrast: Object.fromEntries(Object.entries(C).map(([k, v]) => [k, { onWhite: +contrast(v, C.white).toFixed(2), onInk: +contrast(v, C.ink).toFixed(2) }])),
  eOnFlameMid: +contrast(E_FILL, '#FF4626').toFixed(2),
}, null, 1))
