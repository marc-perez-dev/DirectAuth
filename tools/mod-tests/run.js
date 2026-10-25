#!/usr/bin/env node
// Pruebas con mods reales: arranca un servidor de desarrollo con DirectAuth y las mods de un
// escenario, y un cliente NeoForge real (sin pantalla, con Xvfb) que ejecuta el escenario.
// Uso: node run.js [escenario...] (sin argumentos: todos los de mods.json). Ver README.md.
'use strict'
const fs = require('fs')
const path = require('path')
const net = require('net')
const crypto = require('crypto')
const { spawn, execFileSync } = require('child_process')

const HERE = __dirname
const REPO = path.resolve(HERE, '../..')
const RUNS = path.join(REPO, 'runs/mod-tests')
const CACHE = path.join(HERE, 'build/mod-cache')
const MODDEV = path.join(HERE, 'build/moddev')
const MANIFEST = JSON.parse(fs.readFileSync(path.join(HERE, 'mods.json'), 'utf8'))
const SERVER_PORT = 25570
const RCON_PORT = 25580
const RCON_PASSWORD = 'modtests'
const USERNAME = 'ModTester'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const log = (...a) => console.log(...a)

// ---------- Mods ----------

const verified = new Set()
async function download (mod) {
  const file = path.join(CACHE, mod.file)
  if (verified.has(file)) return file
  if (fs.existsSync(file) && sha512(file) === mod.sha512) { verified.add(file); return file }
  fs.mkdirSync(CACHE, { recursive: true })
  log(`  downloading ${mod.file}`)
  const res = await fetch(mod.url)
  if (!res.ok) throw new Error(`${mod.url}: HTTP ${res.status}`)
  fs.writeFileSync(file + '.part', Buffer.from(await res.arrayBuffer()))
  if (sha512(file + '.part') !== mod.sha512) throw new Error(`${mod.file}: checksum mismatch`)
  fs.renameSync(file + '.part', file)
  verified.add(file)
  return file
}
function sha512 (file) {
  const hash = crypto.createHash('sha512')
  const fd = fs.openSync(file, 'r')
  const buf = Buffer.alloc(1 << 20)
  try {
    for (let n; (n = fs.readSync(fd, buf, 0, buf.length, null)) > 0;) hash.update(buf.subarray(0, n))
  } finally {
    fs.closeSync(fd)
  }
  return hash.digest('hex')
}

/**
 * Todas las mods del manifiesto: también las usa la compilación del arnés (solo para compilar).
 * Sus librerías jar-in-jar (Kotlin, módulos de ManasCore...) se extraen en build/mod-cache/nested.
 */
async function fetchAll () {
  const nested = path.join(CACHE, 'nested')
  fs.mkdirSync(nested, { recursive: true })
  for (const mod of Object.values(MANIFEST.mods)) {
    const file = await download(mod)
    for (const dir of ['META-INF/jarjar/*.jar', 'META-INF/jars/*.jar']) {
      try { execFileSync('unzip', ['-o', '-q', '-j', file, dir, '-d', nested], { stdio: 'ignore' }) } catch {} // sin anidados
    }
  }
}

function modsOf (scenario) {
  const ids = new Set()
  const add = (id) => {
    if (ids.has(id)) return
    const mod = MANIFEST.mods[id]
    if (!mod) throw new Error(`unknown mod ${id}`)
    ids.add(id)
    for (const dep of mod.requires || []) add(dep)
  }
  for (const id of MANIFEST.scenarios[scenario].mods) add(id)
  return [...ids].map((id) => ({ id, ...MANIFEST.mods[id] }))
}

// ---------- Build ----------

function gradle (args, project = REPO) {
  execFileSync('bash', [path.join(REPO, 'gradlew'), '-q', '-p', project, ...args], { cwd: REPO, stdio: 'inherit' })
}

/** Los servidores de prueba necesitan la EULA de Minecraft aceptada (la misma que el smoke test). */
function eulaAccepted () {
  return [path.join(RUNS, 'eula.txt'), path.join(REPO, 'runs/smoke-test/eula.txt')]
    .some((f) => fs.existsSync(f) && /^eula=true/m.test(fs.readFileSync(f, 'utf8')))
}

function directAuthJar () {
  if (process.env.DIRECTAUTH_JAR) return path.resolve(process.env.DIRECTAUTH_JAR)
  const libs = path.join(REPO, 'build/libs')
  const jars = fs.readdirSync(libs).filter((f) => /^directauth-neoforge-.*\.jar$/.test(f) && !/sources|javadoc/.test(f))
    .map((f) => path.join(libs, f)).sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs)
  if (!jars.length) throw new Error('DirectAuth jar not found in build/libs')
  return jars[0]
}

/** Comando java de un script de ModDevGradle (runClient.sh / runServer.sh), como lista de argumentos. */
function launchCommand (script) {
  const text = fs.readFileSync(path.join(MODDEV, script), 'utf8')
  const exec = text.slice(text.indexOf('exec ') + 5)
  const args = [...exec.matchAll(/'([^']*)'/g)].map((m) => m[1])
  const main = args.indexOf('net.neoforged.devlaunch.Main')
  if (!args[0].endsWith('/bin/java') || main < 0) throw new Error(`unexpected format in ${script}`)
  return { java: args[0], jvm: args.slice(1, main), main: args.slice(main) }
}

// ---------- Directorios de juego ----------

function prepareDirs (scenario, mods) {
  const dir = path.join(RUNS, scenario)
  const server = path.join(dir, 'server')
  const client = path.join(dir, 'client')
  // Mundo y configuración desde cero en cada pasada (los escenarios asumen cuentas nuevas)
  fs.rmSync(dir, { recursive: true, force: true })
  for (const d of [server, client]) fs.mkdirSync(path.join(d, 'mods'), { recursive: true })

  fs.writeFileSync(path.join(server, 'eula.txt'), 'eula=true\n')
  const props = {
    'online-mode': 'false',
    'server-ip': '127.0.0.1',
    'server-port': SERVER_PORT,
    'enable-rcon': 'true',
    'rcon.port': RCON_PORT,
    'rcon.password': RCON_PASSWORD,
    'level-type': 'minecraft\\:flat',
    'generate-structures': 'false',
    'spawn-protection': '0',
    'view-distance': '4',
    'simulation-distance': '4',
    'enforce-secure-profile': 'false',
    'spawn-monsters': 'false',
    difficulty: 'peaceful',
    motd: 'DirectAuth mod tests',
    ...(MANIFEST.scenarios[scenario].serverProperties || {})
  }
  fs.writeFileSync(path.join(server, 'server.properties'), Object.entries(props).map(([k, v]) => `${k}=${v}`).join('\n') + '\n')
  const jar = directAuthJar()
  fs.copyFileSync(jar, path.join(server, 'mods', path.basename(jar)))

  for (const mod of mods) {
    const src = path.join(CACHE, mod.file)
    if (mod.side !== 'client') fs.copyFileSync(src, path.join(server, 'mods', mod.file))
    if (mod.side !== 'server') {
      fs.copyFileSync(src, path.join(client, 'mods', mod.file))
      if (mod.clientPatch) patchJar(path.join(client, 'mods', mod.file), mod.clientPatch)
    }
  }
  for (const [rel, content] of Object.entries(MANIFEST.scenarios[scenario].serverFiles || {})) {
    fs.mkdirSync(path.dirname(path.join(server, rel)), { recursive: true })
    fs.writeFileSync(path.join(server, rel), content)
  }

  // Cliente: sin pausas al perder el foco, sin avisos de primera vez y con el mínimo de gráficos
  fs.writeFileSync(path.join(client, 'options.txt'), [
    'pauseOnLostFocus:false', 'onboardAccessibility:false', 'skipMultiplayerWarning:true',
    'joinedFirstServer:true', 'tutorialStep:none', 'narrator:0', 'renderDistance:2',
    'simulationDistance:5', 'maxFps:30', 'graphicsMode:0', 'renderClouds:"false"',
    'soundCategory_master:0.0', 'fullscreen:false', 'lang:en_us'
  ].join('\n') + '\n')
  return { dir, server, client }
}

/** Retoque de un fichero del servidor tras el primer arranque: replace (regex), copy, delete o write. */
function applyEdit (serverDir, op) {
  const file = (rel) => path.join(serverDir, rel)
  if (op.replace) {
    const text = fs.readFileSync(file(op.replace), 'utf8')
    const edited = text.replace(new RegExp(op.from, 'm'), op.to)
    if (edited === text) throw new Error(`firstBoot: /${op.from}/ not found in ${op.replace}`)
    fs.writeFileSync(file(op.replace), edited)
  } else if (op.copy) {
    fs.mkdirSync(path.dirname(file(op.to)), { recursive: true })
    fs.copyFileSync(file(op.copy), file(op.to))
  } else if (op.delete) {
    fs.rmSync(file(op.delete), { recursive: true, force: true })
  } else if (op.write) {
    fs.mkdirSync(path.dirname(file(op.write)), { recursive: true })
    fs.writeFileSync(file(op.write), op.content)
  } else {
    throw new Error(`firstBoot: unknown edit ${JSON.stringify(op)}`)
  }
}

/**
 * Quita mixins de la configuración de un jar (o de un jar anidado: jar-in-jar). Para mods cuyo
 * mixin no aplica en el Minecraft recompilado del entorno de desarrollo (ver README).
 */
function patchJar (jar, patch) {
  const work = fs.mkdtempSync(path.join(require('os').tmpdir(), 'modtest-patch-'))
  try {
    let target = jar
    if (patch.nested) {
      execFileSync('unzip', ['-o', '-q', jar, patch.nested, '-d', work])
      target = path.join(work, patch.nested)
    }
    const cfgDir = path.join(work, 'cfg')
    execFileSync('unzip', ['-o', '-q', target, patch.mixinConfig, '-d', cfgDir])
    const cfgFile = path.join(cfgDir, patch.mixinConfig)
    const cfg = JSON.parse(fs.readFileSync(cfgFile, 'utf8'))
    for (const section of ['mixins', 'client', 'server']) {
      if (Array.isArray(cfg[section])) cfg[section] = cfg[section].filter((m) => !patch.removeMixins.includes(m))
    }
    fs.writeFileSync(cfgFile, JSON.stringify(cfg, null, 2))
    execFileSync('zip', ['-q', path.resolve(target), patch.mixinConfig], { cwd: cfgDir })
    if (patch.nested) execFileSync('zip', ['-q', path.resolve(jar), patch.nested], { cwd: work })
  } finally {
    fs.rmSync(work, { recursive: true, force: true })
  }
}

// ---------- Procesos ----------

// Servidor y cliente corren en su propio grupo de procesos: si run.js termina antes (Ctrl-C, error),
// se matan para que no se queden ocupando los puertos y la memoria
const running = new Set()
function killAll () {
  for (const child of running) {
    try { process.kill(-child.pid, 'SIGKILL') } catch {}
  }
}
process.on('exit', killAll)
for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) process.on(signal, () => process.exit(130))

function startProcess (name, cmd, extraJvm, extraArgs, cwd, logFile, wrapper = []) {
  const out = fs.openSync(logFile, 'w')
  const argv = [...extraJvm, ...cmd.jvm, ...cmd.main, ...extraArgs]
  const [bin, ...rest] = wrapper.length ? [...wrapper, cmd.java, ...argv] : [cmd.java, ...argv]
  const child = spawn(bin, rest, { cwd, stdio: ['ignore', out, out], detached: true })
  child.exited = new Promise((resolve) => child.on('exit', (code, signal) => { running.delete(child); resolve(code ?? signal) }))
  child.label = name
  running.add(child)
  return child
}

/** Espera una línea en el log, leyendo solo lo nuevo (el patrón debe caber en una línea). */
async function waitForLog (file, pattern, timeoutMs, child) {
  const end = Date.now() + timeoutMs
  let offset = 0
  let tail = ''
  while (Date.now() < end) {
    if (fs.existsSync(file)) {
      const size = fs.statSync(file).size
      if (size > offset) {
        const fd = fs.openSync(file, 'r')
        const buf = Buffer.alloc(size - offset)
        fs.readSync(fd, buf, 0, buf.length, offset)
        fs.closeSync(fd)
        offset = size
        const text = tail + buf.toString('utf8')
        if (pattern.test(text)) return true
        tail = text.slice(text.lastIndexOf('\n') + 1)
      }
    }
    // Muerto por código de salida o por señal (p. ej. el OOM killer)
    if (child.exitCode !== null || child.signalCode !== null) return false
    await sleep(500)
  }
  return false
}

function rconCommand (command) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(RCON_PORT, '127.0.0.1')
    const packet = (id, type, body) => {
      const b = Buffer.from(body, 'utf8')
      const buf = Buffer.alloc(14 + b.length)
      buf.writeInt32LE(10 + b.length, 0); buf.writeInt32LE(id, 4); buf.writeInt32LE(type, 8)
      b.copy(buf, 12)
      return buf
    }
    let data = Buffer.alloc(0)
    let stage = 0
    socket.setTimeout(10000, () => { socket.destroy(); reject(new Error('rcon timeout')) })
    socket.on('error', reject)
    socket.on('connect', () => socket.write(packet(1, 3, RCON_PASSWORD)))
    socket.on('data', (chunk) => {
      data = Buffer.concat([data, chunk])
      while (data.length >= 4 && data.length >= 4 + data.readInt32LE(0)) {
        const len = data.readInt32LE(0)
        const body = data.slice(12, 4 + len - 2).toString('utf8')
        data = data.slice(4 + len)
        if (stage === 0) { stage = 1; socket.write(packet(2, 2, command)) } else { socket.end(); resolve(body) }
      }
    })
  })
}

async function stopProcess (child, gracefully) {
  if (child.exitCode !== null) return
  if (gracefully) {
    await gracefully().catch(() => {})
    const code = await Promise.race([child.exited, sleep(60000).then(() => 'timeout')])
    if (code !== 'timeout') return
  }
  try { process.kill(-child.pid, 'SIGKILL') } catch {}
  await child.exited
}

// ---------- Escenario ----------

async function runScenario (scenario) {
  const def = MANIFEST.scenarios[scenario]
  if (!def) throw new Error(`unknown scenario ${scenario}`)
  const mods = modsOf(scenario)
  for (const mod of mods) await download(mod)
  const dirs = prepareDirs(scenario, mods)
  const results = path.join(dirs.dir, 'results.jsonl')
  const serverLog = path.join(dirs.dir, 'server.log')
  const clientLog = path.join(dirs.dir, 'client.log')

  log(`=== ${scenario}${mods.length ? ' (' + mods.map((m) => m.id).join(', ') + ')' : ''}`)
  const startServer = async (logFile) => {
    const child = startProcess('server', launchCommand('runServer.sh'),
      [`-Xmx${def.serverMemory || '2G'}`, '-Ddirectauth.modtests=server'], ['--nogui'], dirs.server, logFile)
    if (!await waitForLog(logFile, /RCON running/, 600000, child)) {
      await stopProcess(child)
      throw new Error(`server did not start, see ${logFile}`)
    }
    return child
  }
  // Algunas mods solo escriben su configuración al arrancar: un primer arranque la genera y se
  // retoca antes de la pasada de verdad (nadie ha entrado todavía al mundo)
  if (def.firstBoot) {
    const first = await startServer(path.join(dirs.dir, 'server-first-boot.log'))
    await stopProcess(first, () => rconCommand('stop'))
    for (const op of def.firstBoot) applyEdit(dirs.server, op)
  }
  const server = await startServer(serverLog)
  let client = null
  try {
    for (const cmd of def.serverSetup || []) await rconCommand(cmd)
    client = startProcess('client', launchCommand('runClient.sh'), [
      `-Xmx${def.clientMemory || '2G'}`,
      ...(def.clientJvmArgs || []),
      `-Ddirectauth.modtest.scenario=${scenario}`,
      `-Ddirectauth.modtest.out=${results}`,
      `-Ddirectauth.modtest.server=127.0.0.1:${SERVER_PORT}`,
      `-Ddirectauth.modtest.rcon=${RCON_PORT}`
    ], ['--username', USERNAME], dirs.client, clientLog,
    ['xvfb-run', '-a', '-s', '-screen 0 1280x720x24'])
    const code = await Promise.race([client.exited, sleep((def.timeoutMinutes || 15) * 60000).then(() => 'timeout')])
    if (code === 'timeout') log(`  client timed out, see ${clientLog}`)
  } finally {
    if (client) await stopProcess(client)
    await stopProcess(server, () => rconCommand('stop'))
  }
  return report(scenario, results, clientLog)
}

function report (scenario, results, clientLog) {
  const lines = fs.existsSync(results) ? fs.readFileSync(results, 'utf8').trim().split('\n').filter(Boolean).map((l) => JSON.parse(l)) : []
  if (!lines.length) {
    log(`FAIL  ${scenario}: no results (client crashed?), see ${clientLog}`)
    return false
  }
  for (const r of lines) log(`${r.ok ? 'PASS' : 'FAIL'}  ${scenario}: ${r.check}${r.ok || !r.detail ? '' : ' — ' + r.detail}`)
  return lines.some((r) => r.check === 'scenario finished') && lines.every((r) => r.ok)
}

;(async () => {
  const args = process.argv.slice(2)
  const scenarios = args.length ? args : Object.keys(MANIFEST.scenarios)
  if (!eulaAccepted()) {
    log('Read the Minecraft EULA (https://aka.ms/MinecraftEULA) and accept it with:')
    log(`  mkdir -p '${RUNS}' && echo eula=true > '${path.join(RUNS, 'eula.txt')}'`)
    process.exit(1)
  }
  // DIRECTAUTH_JAR=<jar> prueba ese jar (p. ej. una release) en vez de compilar este checkout
  log('Building DirectAuth and the harness')
  if (!process.env.DIRECTAUTH_JAR) gradle(['jar'])
  await fetchAll()
  gradle(['classes', 'createClientLaunchScript', 'createServerLaunchScript'], HERE)
  const failed = []
  for (const s of scenarios) {
    try {
      if (!await runScenario(s)) failed.push(s)
    } catch (e) {
      log(`FAIL  ${s}: ${e.message}`)
      failed.push(s)
    }
  }
  log(failed.length ? `\nFAILED: ${failed.join(', ')}` : '\nALL PASSED')
  process.exit(failed.length ? 1 : 0)
})()
