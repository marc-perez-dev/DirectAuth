#!/usr/bin/env node
// Pruebas con mods reales: arranca un servidor Forge 1.20.1 de producción con DirectAuth y las mods
// de un escenario, y un cliente Forge real (de producción, sin pantalla, con Xvfb) que ejecuta el
// escenario.
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
// Servidor Forge de producción, instalado una vez y compartido por los escenarios (sus librerías)
const FORGE = '1.20.1-47.4.0'
const FORGE_HOME = path.join(RUNS, '_forge')
const FORGE_ARGS = `libraries/net/minecraftforge/forge/${FORGE}/unix_args.txt`
const [MC, FORGE_BUILD] = FORGE.split('-')
const CLIENT_HOME = path.join(RUNS, '_forge-client')
const CLIENT_VERSION = `${MC}-forge-${FORGE_BUILD}`
const MANIFEST = JSON.parse(fs.readFileSync(path.join(HERE, 'mods.json'), 'utf8'))
const SERVER_PORT = 25570
const RCON_PORT = 25580
const RCON_PASSWORD = 'modtests'
const USERNAME = 'ModTester'
// El arnés reobfuscado (con los comandos /mt) para el servidor de producción
const HARNESS_JAR = path.join(HERE, 'build/libs/directauth_modtests-1.0.0.jar')

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const log = (...a) => console.log(...a)

// ---------- Mods ----------

const verified = new Set()
async function download (mod) {
  const file = path.join(CACHE, mod.file)
  if (verified.has(file)) return file
  if (fs.existsSync(file) && hashFile('sha512', file) === mod.sha512) { verified.add(file); return file }
  fs.mkdirSync(CACHE, { recursive: true })
  log(`  downloading ${mod.file}`)
  const res = await fetch(mod.url)
  if (!res.ok) throw new Error(`${mod.url}: HTTP ${res.status}`)
  fs.writeFileSync(file + '.part', Buffer.from(await res.arrayBuffer()))
  if (hashFile('sha512', file + '.part') !== mod.sha512) throw new Error(`${mod.file}: checksum mismatch`)
  fs.renameSync(file + '.part', file)
  verified.add(file)
  return file
}
function hashFile (algorithm, file) {
  const hash = crypto.createHash(algorithm)
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
  execFileSync('bash', [path.join(REPO, 'gradlew'), '-q', '-p', project, '-Dorg.gradle.jvmargs=-Xmx2G', ...args], { cwd: REPO, stdio: 'inherit' })
}

/** Java 17, el de Minecraft 1.20.1: JAVA17 o el que descarga el toolchain de Gradle. */
function java17 () {
  if (process.env.JAVA17) return process.env.JAVA17
  const jdks = path.join(process.env.HOME, '.gradle/jdks')
  const dir = fs.existsSync(jdks) && fs.readdirSync(jdks).find((d) => /-17-/.test(d) && !d.endsWith('.lock'))
  if (!dir) throw new Error('Java 17 not found: set JAVA17=/path/to/java')
  return path.join(jdks, dir, 'bin/java')
}

/** Descarga un fichero si falta (o no cuadra su sha1, si se conoce). */
async function fetchFile (url, file, sha1) {
  if (fs.existsSync(file) && (!sha1 || hashFile('sha1', file) === sha1)) return
  fs.mkdirSync(path.dirname(file), { recursive: true })
  const res = await fetch(url)
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`)
  fs.writeFileSync(file + '.part', Buffer.from(await res.arrayBuffer()))
  if (sha1 && hashFile('sha1', file + '.part') !== sha1) throw new Error(`${url}: checksum mismatch`)
  fs.renameSync(file + '.part', file)
}

/** Ejecuta el instalador de Forge en dir con la opción dada (--installServer, --installClient). */
async function runForgeInstaller (dir, option) {
  const installer = path.join(dir, `forge-${FORGE}-installer.jar`)
  await fetchFile(`https://maven.minecraftforge.net/net/minecraftforge/forge/${FORGE}/forge-${FORGE}-installer.jar`, installer)
  execFileSync(java17(), ['-jar', installer, option, dir], { cwd: dir, stdio: 'ignore' })
  fs.rmSync(installer)
}

/** Instala el servidor Forge la primera vez (librerías y servidor vanilla parcheado). */
async function installForge () {
  if (fs.existsSync(path.join(FORGE_HOME, FORGE_ARGS))) return
  fs.mkdirSync(FORGE_HOME, { recursive: true })
  log('  installing the Forge server')
  await runForgeInstaller(FORGE_HOME, '--installServer')
}

/**
 * Instala la primera vez un cliente Forge de producción en runs/mod-tests/_forge-client, como lo haría
 * un launcher: el instalador pone Forge y el cliente parcheado; las librerías de vanilla y los
 * assets vienen de Mojang. Es el mismo cliente que usan los jugadores (nombres SRG, mods tal cual
 * se publican).
 */
async function installClient () {
  const forgeJson = path.join(CLIENT_HOME, 'versions', CLIENT_VERSION, `${CLIENT_VERSION}.json`)
  if (!fs.existsSync(forgeJson)) {
    fs.mkdirSync(CLIENT_HOME, { recursive: true })
    log('  installing the Forge client')
    // El instalador solo instala en el directorio de un launcher
    fs.writeFileSync(path.join(CLIENT_HOME, 'launcher_profiles.json'), '{"profiles":{}}\n')
    await runForgeInstaller(CLIENT_HOME, '--installClient')
  }
  const vanillaJson = path.join(CLIENT_HOME, 'versions', MC, `${MC}.json`)
  if (!fs.existsSync(vanillaJson)) {
    const res = await fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')
    if (!res.ok) throw new Error(`version manifest: HTTP ${res.status}`)
    const entry = (await res.json()).versions.find((v) => v.id === MC)
    await fetchFile(entry.url, vanillaJson, entry.sha1)
  }
  const vanilla = readJson(vanillaJson)
  for (const lib of vanilla.libraries) {
    const a = lib.downloads?.artifact
    if (a && allowed(lib.rules)) await fetchFile(a.url, path.join(CLIENT_HOME, 'libraries', a.path), a.sha1)
  }
  const index = path.join(CLIENT_HOME, 'assets/indexes', `${vanilla.assetIndex.id}.json`)
  await fetchFile(vanilla.assetIndex.url, index, vanilla.assetIndex.sha1)
  const objects = Object.values(readJson(index).objects)
  const missing = objects.filter((o) => !fs.existsSync(path.join(CLIENT_HOME, 'assets/objects', o.hash.slice(0, 2), o.hash)))
  if (missing.length) log(`  downloading ${missing.length} assets`)
  for (let i = 0; i < missing.length; i += 16) {
    await Promise.all(missing.slice(i, i + 16).map((o) => fetchFile(`https://resources.download.minecraft.net/${o.hash.slice(0, 2)}/${o.hash}`,
      path.join(CLIENT_HOME, 'assets/objects', o.hash.slice(0, 2), o.hash), o.hash)))
  }
}

const readJson = (file) => JSON.parse(fs.readFileSync(file, 'utf8'))

/** Reglas de las librerías y argumentos de una versión: solo Linux, sin características opcionales. */
function allowed (rules) {
  if (!rules) return true
  let ok = false
  for (const rule of rules) {
    const os = !rule.os || ((!rule.os.name || rule.os.name === 'linux') && (!rule.os.arch || rule.os.arch === 'x86_64'))
    if (os && !rule.features) ok = rule.action === 'allow'
  }
  return ok
}

/** Los servidores de prueba necesitan la EULA de Minecraft aceptada (la misma que el smoke test). */
function eulaAccepted () {
  return [path.join(RUNS, 'eula.txt'), path.join(REPO, 'runs/smoke-test/eula.txt')]
    .some((f) => fs.existsSync(f) && /^eula=true/m.test(fs.readFileSync(f, 'utf8')))
}

function directAuthJar () {
  if (process.env.DIRECTAUTH_JAR) return path.resolve(process.env.DIRECTAUTH_JAR)
  const libs = path.join(REPO, 'build/libs')
  const version = fs.readFileSync(path.join(REPO, 'gradle.properties'), 'utf8').match(/^mod_version=(.*)$/m)[1].trim()
  const jars = fs.readdirSync(libs).filter((f) => f === `directauth-forge-1.20.1-${version}.jar`)
    .map((f) => path.join(libs, f)).sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs)
  if (!jars.length) throw new Error('DirectAuth jar not found in build/libs')
  return jars[0]
}

/**
 * Comando java del cliente de producción (ver installClient), como lo arma un launcher: las
 * versiones de Forge y de vanilla, con las librerías de Forge por delante si se repiten.
 */
function clientCommand (gameDir) {
  const vanilla = readJson(path.join(CLIENT_HOME, 'versions', MC, `${MC}.json`))
  const forge = readJson(path.join(CLIENT_HOME, 'versions', CLIENT_VERSION, `${CLIENT_VERSION}.json`))
  const libraries = path.join(CLIENT_HOME, 'libraries')
  const classpath = []
  const seen = new Set()
  for (const lib of [...forge.libraries, ...vanilla.libraries]) {
    const a = lib.downloads?.artifact
    const [group, artifact, , classifier] = lib.name.split(':')
    const key = `${group}:${artifact}:${classifier || ''}`
    if (!a || !allowed(lib.rules) || seen.has(key)) continue
    seen.add(key)
    classpath.push(path.join(libraries, a.path))
  }
  const natives = path.join(CLIENT_HOME, 'natives')
  fs.mkdirSync(natives, { recursive: true })
  const vars = {
    library_directory: libraries,
    classpath_separator: ':',
    classpath: classpath.join(':'),
    natives_directory: natives,
    launcher_name: 'directauth-modtests',
    launcher_version: '1',
    version_name: CLIENT_VERSION,
    version_type: 'release',
    game_directory: gameDir,
    assets_root: path.join(CLIENT_HOME, 'assets'),
    assets_index_name: vanilla.assetIndex.id,
    auth_player_name: USERNAME,
    auth_uuid: '00000000000000000000000000000000',
    auth_access_token: '0',
    clientid: '0',
    auth_xuid: '0',
    user_type: 'legacy'
  }
  const expand = (args) => args.flatMap((arg) => typeof arg === 'string' ? [arg] : allowed(arg.rules) ? [arg.value].flat() : [])
    .map((arg) => arg.replace(/\$\{(\w+)\}/g, (m, name) => vars[name] ?? m))
  return {
    java: java17(),
    jvm: [...expand(vanilla.arguments.jvm), ...expand(forge.arguments.jvm)],
    main: [forge.mainClass, ...expand(vanilla.arguments.game), ...expand(forge.arguments.game)]
  }
}

/** Comando java del servidor de producción, desde el directorio del escenario. */
function serverCommand () {
  return { java: java17(), jvm: [], main: [`@${FORGE_ARGS}`] }
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
  // Servidor de producción: las librerías de Forge son las de la instalación compartida
  fs.symlinkSync(path.join(FORGE_HOME, 'libraries'), path.join(server, 'libraries'))
  const jar = directAuthJar()
  fs.copyFileSync(jar, path.join(server, 'mods', path.basename(jar)))
  for (const d of [server, client]) fs.copyFileSync(HARNESS_JAR, path.join(d, 'mods', path.basename(HARNESS_JAR)))

  // Servidor y cliente de producción: las mods tal cual se publican
  for (const mod of mods) {
    if (mod.side !== 'client') fs.copyFileSync(path.join(CACHE, mod.file), path.join(server, 'mods', mod.file))
    if (mod.side !== 'server') fs.copyFileSync(path.join(CACHE, mod.file), path.join(client, 'mods', mod.file))
  }
  for (const [files, d] of [['serverFiles', server], ['clientFiles', client]]) {
    for (const [rel, content] of Object.entries(MANIFEST.scenarios[scenario][files] || {})) {
      fs.mkdirSync(path.dirname(path.join(d, rel)), { recursive: true })
      fs.writeFileSync(path.join(d, rel), content)
    }
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
    // Un servidor que se cae o se detiene puede cerrar sin responder: sin esto la promesa no acabaría
    socket.on('close', () => reject(new Error('rcon connection closed without a reply')))
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
    const child = startProcess('server', serverCommand(),
      [`-Xmx${def.serverMemory || '2G'}`], ['nogui'], dirs.server, logFile)
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
    client = startProcess('client', clientCommand(dirs.client), [
      `-Xmx${def.clientMemory || '2G'}`,
      ...(def.clientJvmArgs || []),
      `-Ddirectauth.modtest.scenario=${scenario}`,
      `-Ddirectauth.modtest.out=${results}`,
      `-Ddirectauth.modtest.server=127.0.0.1:${SERVER_PORT}`,
      `-Ddirectauth.modtest.rcon=${RCON_PORT}`
    ], [], dirs.client, clientLog,
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
  if (!process.env.DIRECTAUTH_JAR) gradle(['build'])
  await fetchAll()
  await installForge()
  await installClient()
  // remapMods crea una tarea por jar de build/mod-cache: tiene que ir después de fetchAll
  gradle(['reobfJar'], HERE)
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
