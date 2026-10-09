// One-off, already run: traces the serif "e" of the eRestyu icon into
// serif-e.json, which build.js uses. You only need this again to change the
// letter itself.
//
// It outlines Georgia Bold at exactly the geometry favicon.svg always drew
// with live text: font-size 16.5 on a 24-unit tile, text-anchor middle (which
// centres the glyph's ADVANCE, not its ink), baseline at y 17.3, scaled to the
// 512-unit tile build.js works in. The result was pixel-compared against the
// live-text icon before use.
//
// Georgia is a Microsoft font installed with Windows, so this script needs a
// Windows machine; build.js does not.
const opentype = require('opentype.js')
const fs = require('fs')
const path = require('path')

const f = opentype.loadSync('C:/Windows/Fonts/georgiab.ttf')
const T = 512, k = T / 24
const size = 16.5 * k, baseline = 17.3 * k
const glyph = f.charToGlyph('e')
const adv = glyph.advanceWidth * size / f.unitsPerEm
const p = f.getPath('e', T / 2 - adv / 2, baseline, size)
const bb = p.getBoundingBox()
const out = {
  path: p.toPathData(3),
  bbox: { x1: +bb.x1.toFixed(3), y1: +bb.y1.toFixed(3), x2: +bb.x2.toFixed(3), y2: +bb.y2.toFixed(3) },
}
fs.writeFileSync(path.join(__dirname, 'serif-e.json'), JSON.stringify(out, null, 2))
console.log('traced', f.names.fullName.en, out.bbox)
