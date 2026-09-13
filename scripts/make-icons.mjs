#!/usr/bin/env node
/**
 * Renders the home-screen icon PNGs from web/public/icon.svg.
 *
 * A phone that adds the app to its home screen will not take the SVG: iOS
 * only reads `apple-touch-icon` as a raster image, and Android wants PNGs of
 * known pixel sizes out of the web app manifest. So the mark has to exist as
 * bitmaps too — but it is still drawn exactly once, in icon.svg, and this
 * script re-renders it. Change the SVG, run `make icons`, commit what falls
 * out; never hand-edit the PNGs.
 *
 * No dependencies, deliberately. Pulling a rasteriser into the toolchain to
 * redraw a dozen shapes would cost more than drawing them: the mark is rounded
 * rectangles and round-capped line segments, and both have an exact distance
 * function, so coverage per pixel is arithmetic and the PNG is zlib plus four
 * chunk headers. Node's standard library has everything needed.
 *
 * Usage:
 *   node scripts/make-icons.mjs           # write the PNGs
 *   node scripts/make-icons.mjs --check   # fail if they are stale (CI)
 */
import { createHash } from 'node:crypto'
import { deflateSync } from 'node:zlib'
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const publicDir = join(root, 'web', 'public')
const source = join(publicDir, 'icon.svg')

/**
 * What each variant is for, and why it is shaped the way it is.
 *
 *  - `plate: 'square'` fills the whole canvas and squares off the background
 *    corners. iOS ignores the corner radius an icon draws for itself and masks
 *    its own squircle over it; a rounded icon inside that mask reads as a
 *    shrunken sticker with a dark halo. Transparency is no better — iOS
 *    composites it onto black, so an alpha edge becomes a black fringe.
 *  - `inset` shrinks the artwork inside a full-bleed background for Android's
 *    maskable icons, where the launcher may crop anything outside a circle
 *    80% of the icon's width — so nothing may sit further than 40% of the
 *    width from the centre. The outer corners of the hanging clips sit at
 *    50%, which a round or squircle mask would clip, so the artwork comes in
 *    until they clear the safe zone with room to spare.
 *  - The plain `any` icons keep the SVG's own rounded square on transparency,
 *    for the browsers and launchers that place an icon without masking it.
 */
const VARIANTS = [
  { file: 'apple-touch-icon.png', size: 180, plate: 'square' },
  { file: 'icon-192.png', size: 192, plate: 'rounded' },
  { file: 'icon-512.png', size: 512, plate: 'rounded' },
  { file: 'icon-maskable-512.png', size: 512, plate: 'square', inset: 0.78 },
]

// ---------------------------------------------------------------------------
// Reading the mark
// ---------------------------------------------------------------------------

/**
 * Pulls the shapes out of icon.svg, in paint order.
 *
 * This understands the handful of SVG the mark actually uses — <rect> with a
 * corner radius, <line> with a round cap, a fill or a stroke, and paint
 * attributes inherited from an enclosing <g>. It is not an SVG parser and is
 * not trying to be: if the mark ever grows a shape this cannot express, this
 * script must fail loudly rather than quietly render something that is no
 * longer the logo, which is what the unknown-element check below is for.
 */
function readMark(svg) {
  const viewBox = /viewBox="0 0 (\d+) (\d+)"/.exec(svg)
  if (!viewBox) throw new Error('icon.svg: no square viewBox to render from')
  const [, w, h] = viewBox
  if (w !== h) throw new Error(`icon.svg: viewBox ${w}x${h} is not square`)

  const body = svg.replace(/<!--[\s\S]*?-->/g, '').replace(/<svg[^>]*>|<\/svg>/g, '')
  const shapes = []
  const groups = []

  // Paint is what a <g> is used for here, so paint is what it passes down.
  const painted = (tag, name, fallback) =>
    attr(tag, name) ?? groups.findLast((g) => g[name] !== undefined)?.[name] ?? fallback
  const paintedNum = (tag, name, fallback) => {
    const raw = painted(tag, name, undefined)
    return raw === undefined ? fallback : Number(raw)
  }

  for (const [tag] of body.matchAll(/<[^>]+>/g)) {
    if (/^<g[\s>]/.test(tag)) {
      groups.push(Object.fromEntries(
        INHERITED.map((name) => [name, attr(tag, name)]).filter(([, v]) => v !== undefined)))
      continue
    }
    if (/^<\/g>/.test(tag)) {
      groups.pop()
      continue
    }

    const stroke = painted(tag, 'stroke', null)
    const common = {
      stroke: stroke === 'none' ? null : stroke,
      strokeWidth: paintedNum(tag, 'stroke-width', 0),
      opacity: paintedNum(tag, 'opacity', 1),
    }

    if (/^<rect[\s>]/.test(tag)) {
      const fill = painted(tag, 'fill', null)
      shapes.push({
        kind: 'rect',
        x: paintedNum(tag, 'x', 0),
        y: paintedNum(tag, 'y', 0),
        w: paintedNum(tag, 'width', 0),
        h: paintedNum(tag, 'height', 0),
        r: paintedNum(tag, 'rx', 0),
        fill: fill === 'none' ? null : fill,
        ...common,
      })
      continue
    }

    if (/^<line[\s>]/.test(tag)) {
      // The segment distance below is measured to the centre line, so the
      // stroke it grows into is round-capped by construction. A butt or square
      // cap would be a different shape, and silently drawing the wrong one is
      // exactly the failure this script refuses to make.
      const cap = painted(tag, 'stroke-linecap', 'round')
      if (cap !== 'round') {
        throw new Error(`icon.svg: stroke-linecap="${cap}" is not a cap this script can draw`)
      }
      if (!common.stroke || !(common.strokeWidth > 0)) {
        throw new Error('icon.svg: a <line> with no stroke draws nothing')
      }
      shapes.push({
        kind: 'line',
        x1: paintedNum(tag, 'x1', 0),
        y1: paintedNum(tag, 'y1', 0),
        x2: paintedNum(tag, 'x2', 0),
        y2: paintedNum(tag, 'y2', 0),
        ...common,
      })
      continue
    }

    throw new Error(`icon.svg: ${tag.slice(0, 40)} is not a shape this script can draw`)
  }

  if (!shapes.length) throw new Error('icon.svg: nothing to draw')
  return { grid: Number(w), shapes }
}

const attr = (tag, name) => new RegExp(`\\s${name}="([^"]*)"`).exec(tag)?.[1]
// The properties a shape may take from an enclosing <g>.
const INHERITED = ['fill', 'stroke', 'stroke-width', 'stroke-linecap', 'opacity']

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

/**
 * Signed distance from a point to a rounded rectangle: negative inside,
 * positive outside, measured in the same units as the box. Turning that into
 * coverage (below) antialiases every edge for free and exactly, which is why
 * this draws shapes rather than supersampling them.
 */
function roundedRectDistance(px, py, cx, cy, halfW, halfH, radius) {
  const r = Math.min(radius, halfW, halfH)
  const qx = Math.abs(px - cx) - (halfW - r)
  const qy = Math.abs(py - cy) - (halfH - r)
  const outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0))
  return outside + Math.min(Math.max(qx, qy), 0) - r
}

/**
 * Distance from a point to a line segment, in the same units as the segment.
 * Growing the stroke out of this gives round caps and round joins for free,
 * which is the only cap readMark accepts.
 */
function segmentDistance(px, py, ax, ay, bx, by) {
  const dx = bx - ax
  const dy = by - ay
  const length2 = dx * dx + dy * dy
  // A zero-length segment is a single point, and the projection below would
  // divide by zero; the distance to it is just the distance to that point.
  const t = length2 === 0
    ? 0
    : Math.min(Math.max(((px - ax) * dx + (py - ay) * dy) / length2, 0), 1)
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
}

/** How much of a pixel a shape covers, given the distance to its edge. */
const coverage = (distance) => Math.min(Math.max(0.5 - distance, 0), 1)

function parseColor(hex) {
  const v = hex.replace('#', '')
  const full = v.length === 3 ? [...v].map((c) => c + c).join('') : v
  return [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16))
}

/** Paints `shape` (a coverage function) over the canvas in source-over order. */
function paint(pixels, size, color, alpha, distanceAt) {
  const [r, g, b] = parseColor(color)
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const a = coverage(distanceAt(x + 0.5, y + 0.5)) * alpha
      if (a <= 0) continue

      const i = (y * size + x) * 4
      const dstA = pixels[i + 3] / 255
      const outA = a + dstA * (1 - a)
      // Un-premultiplied source-over. outA is non-zero because a is.
      pixels[i] = Math.round((r * a + pixels[i] * dstA * (1 - a)) / outA)
      pixels[i + 1] = Math.round((g * a + pixels[i + 1] * dstA * (1 - a)) / outA)
      pixels[i + 2] = Math.round((b * a + pixels[i + 2] * dstA * (1 - a)) / outA)
      pixels[i + 3] = Math.round(outA * 255)
    }
  }
}

function render(mark, { size, plate, inset = 1 }) {
  const pixels = new Uint8Array(size * size * 4)
  const scale = size / mark.grid
  const centre = mark.grid / 2
  // Pulls a coordinate towards the centre of the grid, then onto the canvas —
  // how the maskable variant brings the artwork inside its safe zone.
  const place = (v, shrink) => (centre + (v - centre) * shrink) * scale

  for (const shape of mark.shapes) {
    if (shape.kind === 'line') {
      const ax = place(shape.x1, inset)
      const ay = place(shape.y1, inset)
      const bx = place(shape.x2, inset)
      const by = place(shape.y2, inset)
      // A stroke straddles the path: half its width falls either side.
      const half = (shape.strokeWidth * inset * scale) / 2
      paint(pixels, size, shape.stroke, shape.opacity, (px, py) =>
        segmentDistance(px, py, ax, ay, bx, by) - half)
      continue
    }

    // The rectangle that covers the whole viewBox is the background plate:
    // it is the one variants reshape, and the one the artwork sits inside.
    const isPlate = shape.w >= mark.grid && shape.h >= mark.grid
    const shrink = isPlate ? 1 : inset

    const w = shape.w * shrink
    const h = shape.h * shrink
    const radius = (isPlate && plate === 'square' ? 0 : shape.r) * shrink

    const cx = place(shape.x, shrink) + (w / 2) * scale
    const cy = place(shape.y, shrink) + (h / 2) * scale
    const halfW = (w / 2) * scale
    const halfH = (h / 2) * scale
    const r = radius * scale

    if (shape.fill) {
      paint(pixels, size, shape.fill, shape.opacity, (px, py) =>
        roundedRectDistance(px, py, cx, cy, halfW, halfH, r))
    }
    if (shape.stroke && shape.strokeWidth > 0) {
      // Same half-width either side of the edge, so the ring a stroked rect
      // draws is the absolute distance to that edge minus the half-width.
      const half = (shape.strokeWidth * shrink * scale) / 2
      paint(pixels, size, shape.stroke, shape.opacity, (px, py) =>
        Math.abs(roundedRectDistance(px, py, cx, cy, halfW, halfH, r)) - half)
    }
  }

  return pixels
}

// ---------------------------------------------------------------------------
// PNG encoding
// ---------------------------------------------------------------------------

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})

function crc32(buf) {
  let c = 0xffffffff
  for (const byte of buf) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

function chunk(type, data) {
  const head = Buffer.alloc(8)
  head.writeUInt32BE(data.length, 0)
  head.write(type, 4, 'ascii')
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), data])), 0)
  return Buffer.concat([head, data, crc])
}

function encodePNG(pixels, size) {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0)
  ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 6 // truecolour with alpha
  // Compression, filter and interlace methods: the only values PNG defines.

  // Each scanline is prefixed with its filter type. These are flat colour on
  // flat colour, so leaving them unfiltered still deflates to a few kilobytes.
  const stride = size * 4
  const raw = Buffer.alloc(size * (stride + 1))
  for (let y = 0; y < size; y++) {
    raw[y * (stride + 1)] = 0
    Buffer.from(pixels.buffer, y * stride, stride).copy(raw, y * (stride + 1) + 1)
  }

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

// ---------------------------------------------------------------------------

const check = process.argv.includes('--check')
const mark = readMark(readFileSync(source, 'utf8'))
let stale = 0

for (const variant of VARIANTS) {
  const png = encodePNG(render(mark, variant), variant.size)
  const path = join(publicDir, variant.file)

  if (check) {
    let current = null
    try {
      current = readFileSync(path)
    } catch { /* missing counts as stale */ }
    const same = current && createHash('sha256').update(current).digest('hex')
      === createHash('sha256').update(png).digest('hex')
    if (!same) {
      console.error(`stale: ${variant.file} does not match icon.svg`)
      stale++
    }
    continue
  }

  writeFileSync(path, png)
  console.log(`${variant.file.padEnd(24)} ${variant.size}px  ${(png.length / 1024).toFixed(1)} KB`)
}

if (check) {
  if (stale) {
    console.error(`\n${stale} icon(s) out of date — run: make icons`)
    process.exit(1)
  }
  console.log('icons match icon.svg')
}
