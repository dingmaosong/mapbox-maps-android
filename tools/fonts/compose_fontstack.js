const fs = require('fs')
const path = require('path')

const composite = require('@mapbox/glyph-pbf-composite')

function arg(name, def) {
  const i = process.argv.indexOf(name)
  if (i !== -1 && process.argv[i + 1]) return process.argv[i + 1]
  return def
}

function argFlag(name) {
  return process.argv.includes(name)
}

function ensureDir(p) {
  fs.mkdirSync(p, { recursive: true })
}

function readPbfOrEmpty(p) {
  if (!fs.existsSync(p)) return null
  const data = fs.readFileSync(p)
  if (data.length >= 15 && data.slice(0, 15).toString('utf8') === '<!DOCTYPE html>') {
    throw new Error(`PBF 文件疑似为 HTML 错误页: ${p}`)
  }
  return data
}

function listRangesForFont(glyphsDir, fontName) {
  const dir = path.join(glyphsDir, fontName)
  if (!fs.existsSync(dir)) {
    throw new Error(`缺少字体目录: ${dir}`)
  }
  return fs.readdirSync(dir).filter((f) => f.endsWith('.pbf')).sort()
}

async function main() {
  const glyphsDir = arg('--glyphs-dir', arg('--in', '.'))
  const outDir = arg('--out', glyphsDir)
  const fontstack = arg('--fontstack', null)
  const overwrite = argFlag('--overwrite')

  if (!fontstack) {
    throw new Error('缺少 --fontstack，例如: "Open Sans Regular,Noto Sans Regular"')
  }

  const fonts = fontstack.split(',').map((s) => s.trim()).filter(Boolean)
  if (fonts.length < 2) {
    throw new Error('fontstack 至少需要 2 个字体，用逗号拼接')
  }

  const normalizedFontstack = fonts.join(',')
  const ranges = listRangesForFont(glyphsDir, fonts[0])
  const outStackDir = path.join(outDir, normalizedFontstack)
  ensureDir(outStackDir)

  for (const rangeFile of ranges) {
    const outFile = path.join(outStackDir, rangeFile)
    if (!overwrite && fs.existsSync(outFile)) continue

    const buffers = []
    for (const fontName of fonts) {
      const p = path.join(glyphsDir, fontName, rangeFile)
      const b = readPbfOrEmpty(p)
      if (b) buffers.push(b)
    }

    if (buffers.length === 0) {
      continue
    }

    const out = composite(buffers)
    fs.writeFileSync(outFile, out)
  }
}

main().catch((e) => {
  process.stderr.write(`❌ ${e && e.message ? e.message : String(e)}\n`)
  process.exit(1)
})
