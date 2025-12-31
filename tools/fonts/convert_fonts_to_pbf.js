const fs = require('fs')
const path = require('path')
const { spawn } = require('child_process')

function arg(name, def) {
  const i = process.argv.indexOf(name)
  if (i !== -1 && process.argv[i + 1]) return process.argv[i + 1]
  return def
}

function resolveExecutable(name) {
  if (process.env.BUILD_PBF_GLYPHS_BIN && fs.existsSync(process.env.BUILD_PBF_GLYPHS_BIN)) {
    return process.env.BUILD_PBF_GLYPHS_BIN
  }

  const candidates = [
    path.join(process.env.HOME || '', '.cargo', 'bin', name),
    name
  ]

  for (const c of candidates) {
    if (c === name) return c
    if (c && fs.existsSync(c)) return c
  }
  return null
}

async function main() {
  const fontDir = arg('--font-dir', '.')
  const outDir = arg('--out', '.')
  const overwrite = process.argv.includes('--overwrite')

  const exe = resolveExecutable('build_pbf_glyphs')
  if (!exe) {
    throw new Error('未找到 build_pbf_glyphs，可先执行: cargo install build_pbf_glyphs --locked')
  }

  const args = []
  if (overwrite) args.push('--overwrite')
  args.push(fontDir, outDir)

  await new Promise((resolve, reject) => {
    const child = spawn(exe, args, { stdio: 'inherit' })
    child.on('error', reject)
    child.on('close', (code) => {
      if (code === 0) resolve()
      else reject(new Error(`build_pbf_glyphs 退出码: ${code}`))
    })
  })
}

main().catch((e) => {
  process.stderr.write(`❌ ${e && e.message ? e.message : String(e)}\n`)
  process.exit(1)
})
