#!/usr/bin/env node
// Pruebas con mods reales: arranca un servidor Fabric 1.21.1 de producción con DirectAuth y las mods
// de un escenario, y un cliente Fabric real (de producción, sin pantalla, con Xvfb) que ejecuta el
// escenario.
// Uso: node run.js [escenario...] (sin argumentos: todos los de mods.json). Ver README.md.
'use strict'
const fs = require('fs')
const path = require('path')
const net = require('net')
const zlib = require('zlib')
const crypto = require('crypto')
const { spawn, execFileSync } = require('child_process')

const HERE = __dirname
const REPO = path.resolve(HERE, '../..')
// Directorio propio: runs/mod-tests es el de las ramas de NeoForge y Forge
const RUNS = path.join(REPO, 'runs/mod-tests-fabric')
const CACHE = path.join(HERE, 'build/mod-cache')
const FABRIC_CACHE = path.join(HERE, 'build/fabric-cache')
const MANIFEST = JSON.parse(fs.readFileSync(path.join(HERE, 'mods.json'), 'utf8'))
// Minecraft, Fabric Loader y Fabric API: los de DirectAuth (gradle.properties de la raíz)
const PROPS = Object.fromEntries(fs.readFileSync(path.join(REPO, 'gradle.properties'), 'utf8').split('\n')
  .map((l) => l.match(/^([\w.]+)=(.*)$/)).filter(Boolean).map((m) => [m[1], m[2].trim()]))
const MC = PROPS.minecraft_version
const LOADER = PROPS.loader_version
const FABRIC_API = PROPS.fabric_api_version
const SERVER_LAUNCHER_VERSION = PROPS.fabric_server_launcher_version
// Instalaciones compartidas por los escenarios: el lanzador de servidor de Fabric con el servidor
// vanilla y sus librerías, y un cliente como el de un launcher (librerías, assets)
const SERVER_HOME = path.join(RUNS, '_server')
const SERVER_LAUNCHER = `fabric-server-mc.${MC}-loader.${LOADER}-launcher.${SERVER_LAUNCHER_VERSION}.jar`
const CLIENT_HOME = path.join(RUNS, '_client')
const FABRIC_PROFILE = `fabric-loader-${LOADER}-${MC}`
const SERVER_PORT = 25570
const RCON_PORT = 25580
const RCON_PASSWORD = 'modtests'
const USERNAME = 'ModTester'
// El arnés remapeado a intermediary (con los comandos /mt) para el servidor y el cliente de producción
const HARNESS_JAR = path.join(HERE, 'build/libs/directauth_modtests-1.0.0.jar')

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const log = (...a) => console.log(...a)
const readJson = (file) => JSON.parse(fs.readFileSync(file, 'utf8'))

// ---------- Descargas ----------

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

/** Descarga un fichero si falta o no cuadra su hash ({sha1} o {sha512}, si se conoce). */
async function fetchFile (url, file, expected = {}) {
  const [algorithm, hash] = Object.entries(expected).find(([, v]) => v) || []
  if (fs.existsSync(file) && (!algorithm || hashFile(algorithm, file) === hash)) return file
  fs.mkdirSync(path.dirname(file), { recursive: true })
  // Unos pocos reintentos: los miles de assets del cliente se descargan de uno en uno
  for (let attempt = 1; ; attempt++) {
    try {
      const res = await fetch(url, { headers: { 'User-Agent': 'marcp/DirectAuth-modtests' } })
      if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`)
      fs.writeFileSync(file + '.part', Buffer.from(await res.arrayBuffer()))
      break
    } catch (e) {
      if (attempt >= 4) throw e
      await sleep(2000 * attempt)
    }
  }
  if (algorithm && hashFile(algorithm, file + '.part') !== hash) throw new Error(`${url}: checksum mismatch`)
  fs.renameSync(file + '.part', file)
  return file
}

/**
 * Como fetchFile, pero un fichero que ya existe no se vuelve a comprobar: fetchFile solo lo deja en
 * su sitio después de comprobar el hash, así que existir quiere decir que se comprobó al instalarlo.
 */
async function ensureFile (url, file, expected) {
  if (!fs.existsSync(file)) await fetchFile(url, file, expected)
  return file
}

const verified = new Set()
async function download (mod) {
  const file = path.join(CACHE, mod.file)
  if (verified.has(file)) return file
  // Un solo hash del jar de la caché; si no cuadra se borra y fetchFile lo descarga y comprueba
  if (fs.existsSync(file) && hashFile('sha512', file) !== mod.sha512) fs.rmSync(file)
  if (!fs.existsSync(file)) {
    log(`  downloading ${mod.file}`)
    await fetchFile(mod.url, file, { sha512: mod.sha512 })
  }
  verified.add(file)
  return file
}

/** Fabric API (la versión de DirectAuth), comprobada con el sha1 que publica su maven. */
async function fabricApi () {
  const v = encodeURIComponent(FABRIC_API)
  const url = `https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${v}/fabric-api-${v}.jar`
  const file = path.join(FABRIC_CACHE, `fabric-api-${FABRIC_API}.jar`)
  if (fs.existsSync(file)) return file
  const res = await fetch(url + '.sha1')
  if (!res.ok) throw new Error(`${url}.sha1: HTTP ${res.status}`)
  return fetchFile(url, file, { sha1: (await res.text()).trim().split(/\s/)[0] })
}

// ---------- Jars (zip) ----------

/** Entradas de un zip (directorio central), sin descomprimir: [{ name, read() }]. */
function zipEntries (buf) {
  let eocd = buf.length - 22
  while (eocd >= 0 && buf.readUInt32LE(eocd) !== 0x06054b50) eocd--
  if (eocd < 0) throw new Error('not a zip file')
  const count = buf.readUInt16LE(eocd + 10)
  let p = buf.readUInt32LE(eocd + 16)
  if (count === 0xffff || p === 0xffffffff) throw new Error('zip64 jars are not supported')
  const entries = []
  for (let i = 0; i < count; i++) {
    const method = buf.readUInt16LE(p + 10)
    const size = buf.readUInt32LE(p + 20)
    const nameLen = buf.readUInt16LE(p + 28)
    const extraLen = buf.readUInt16LE(p + 30)
    const commentLen = buf.readUInt16LE(p + 32)
    const local = buf.readUInt32LE(p + 42)
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen)
    entries.push({
      name,
      read () {
        const start = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28)
        const data = buf.subarray(start, start + size)
        if (method === 0) return data
        if (method === 8) return zlib.inflateRawSync(data)
        throw new Error(`${name}: unsupported zip method ${method}`)
      }
    })
    p += 46 + nameLen + extraLen + commentLen
  }
  return entries
}

/** fabric.mod.json de un jar (null si no es una mod de Fabric). */
function fabricModJson (entries) {
  const entry = entries.find((e) => e.name === 'fabric.mod.json')
  if (!entry) return null
  // Algunas mods llevan saltos de línea dentro de las cadenas (descripciones)
  return JSON.parse(entry.read().toString('utf8').replace(/[\r\n\t]+/g, ' '))
}

// Versión de las reglas de extracción de extractNested: cambiarla invalida nested.stamp
const NESTED_RULES = 2

/**
 * Jars anidados (jar-in-jar) de una mod, recursivamente: las mods van a nested/ (Loom las remapea
 * para compilar el arnés) y las librerías normales (Kotlin...) a nested-libs/, con el nombre del jar
 * que los contiene delante (dos mods pueden anidar jars distintos con el mismo nombre). Se saltan
 * los módulos de Fabric API (el arnés compila contra la Fabric API de DirectAuth) y MixinExtras (no
 * hace falta para compilar y cada mod lleva su versión).
 */
function extractNested (buf, parent, nested, nestedLibs) {
  const entries = zipEntries(buf)
  for (const jar of fabricModJson(entries)?.jars || []) {
    const entry = entries.find((e) => e.name === jar.file)
    if (!entry) continue
    const data = entry.read()
    const meta = fabricModJson(zipEntries(data))
    const fabricApiModule = meta && (meta.id === 'fabric-api' || meta.custom?.['fabric-api:module-lifecycle'] !== undefined)
    if (fabricApiModule || meta?.id === 'mixinextras') continue
    // Loom da a las librerías que anida un fabric.mod.json generado: no son mods, no se remapean
    const isMod = meta && !meta.custom?.['fabric-loom:generated']
    const name = `${parent}__${path.basename(jar.file)}`
    fs.writeFileSync(path.join(isMod ? nested : nestedLibs, name), data)
    extractNested(data, path.basename(jar.file, '.jar'), nested, nestedLibs)
  }
}

/** Todas las mods del manifiesto: también las usa la compilación del arnés (solo para compilar). */
async function fetchAll () {
  const nested = path.join(CACHE, 'nested')
  const nestedLibs = path.join(CACHE, 'nested-libs')
  for (const d of [nested, nestedLibs]) fs.mkdirSync(d, { recursive: true })
  const stamp = path.join(CACHE, 'nested.stamp')
  const expected = [`rules ${NESTED_RULES}`, ...Object.values(MANIFEST.mods).map((m) => m.sha512).sort()].join('\n')
  for (const mod of Object.values(MANIFEST.mods)) await download(mod)
  if (fs.existsSync(stamp) && fs.readFileSync(stamp, 'utf8') === expected) return
  for (const d of [nested, nestedLibs]) {
    fs.rmSync(d, { recursive: true, force: true })
    fs.mkdirSync(d, { recursive: true })
  }
  // Solo las mods del manifiesto: un jar de una versión anterior que siga en la caché no se compila
  for (const f of fs.readdirSync(CACHE)) {
    if (f.endsWith('.jar') && !Object.values(MANIFEST.mods).some((m) => m.file === f)) fs.rmSync(path.join(CACHE, f))
  }
  for (const mod of Object.values(MANIFEST.mods)) extractNested(fs.readFileSync(path.join(CACHE, mod.file)), path.basename(mod.file, '.jar'), nested, nestedLibs)
  fs.writeFileSync(stamp, expected)
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
  execFileSync('bash', [path.join(REPO, 'gradlew'), '-q', '--no-daemon', '-p', project, '-Dorg.gradle.jvmargs=-Xmx2G', ...args],
    { cwd: REPO, stdio: 'inherit' })
}

/** Java 21, el de Minecraft 1.21.1: JAVA21 o el java del PATH. */
function java21 () {
  return process.env.JAVA21 || 'java'
}

/** Los servidores de prueba necesitan la EULA de Minecraft aceptada (la misma que el smoke test). */
function eulaAccepted () {
  return [path.join(RUNS, 'eula.txt'), path.join(REPO, 'runs/smoke-test-fabric/eula.txt')]
    .some((f) => fs.existsSync(f) && /^eula=true/m.test(fs.readFileSync(f, 'utf8')))
}

function directAuthJar () {
  if (process.env.DIRECTAUTH_JAR) return path.resolve(process.env.DIRECTAUTH_JAR)
  const file = path.join(REPO, 'build/libs', `directauth-fabric-${MC}-${PROPS.mod_version}.jar`)
  if (!fs.existsSync(file)) throw new Error(`DirectAuth jar not found: ${file}`)
  return file
}

// ---------- Instalación de Fabric ----------

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

/** Ruta de una librería maven (grupo:artefacto:versión[:clasificador]). */
function mavenPath (name) {
  const [group, artifact, version, classifier] = name.split(':')
  return `${group.replace(/\./g, '/')}/${artifact}/${version}/${artifact}-${version}${classifier ? '-' + classifier : ''}.jar`
}

/**
 * Servidor: el lanzador de servidor de Fabric (el mismo que usa el smoke test). Descarga el servidor
 * vanilla y las librerías de Fabric Loader en el primer arranque; cada escenario enlaza las de la
 * instalación compartida (ver prepareDirs).
 */
async function installServer () {
  await fetchFile(`https://meta.fabricmc.net/v2/versions/loader/${MC}/${LOADER}/${SERVER_LAUNCHER_VERSION}/server/jar`,
    path.join(SERVER_HOME, SERVER_LAUNCHER))
  for (const d of ['libraries', 'versions', 'server', 'remappedJars']) fs.mkdirSync(path.join(SERVER_HOME, d), { recursive: true })
}

/**
 * Cliente de producción en runs/mod-tests-fabric/_client, como lo instalaría un launcher con el
 * perfil de Fabric: el cliente vanilla, sus librerías y assets (de Mojang) y Fabric Loader con sus
 * librerías (del perfil de meta.fabricmc.net). Es el mismo cliente que usan los jugadores
 * (intermediary, mods tal cual se publican).
 */
async function installClient () {
  const vanillaJson = path.join(CLIENT_HOME, 'versions', MC, `${MC}.json`)
  if (!fs.existsSync(vanillaJson)) {
    const res = await fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')
    if (!res.ok) throw new Error(`version manifest: HTTP ${res.status}`)
    const entry = (await res.json()).versions.find((v) => v.id === MC)
    await fetchFile(entry.url, vanillaJson, { sha1: entry.sha1 })
  }
  const vanilla = readJson(vanillaJson)
  await ensureFile(vanilla.downloads.client.url, path.join(CLIENT_HOME, 'versions', MC, `${MC}.jar`), { sha1: vanilla.downloads.client.sha1 })
  for (const lib of vanilla.libraries) {
    const a = lib.downloads?.artifact
    if (a && allowed(lib.rules)) await ensureFile(a.url, path.join(CLIENT_HOME, 'libraries', a.path), { sha1: a.sha1 })
  }
  const fabricJson = path.join(CLIENT_HOME, 'versions', FABRIC_PROFILE, `${FABRIC_PROFILE}.json`)
  if (!fs.existsSync(fabricJson)) await fetchFile(`https://meta.fabricmc.net/v2/versions/loader/${MC}/${LOADER}/profile/json`, fabricJson)
  for (const lib of readJson(fabricJson).libraries) {
    await ensureFile(lib.url.replace(/\/?$/, '/') + mavenPath(lib.name), path.join(CLIENT_HOME, 'libraries', mavenPath(lib.name)), { sha1: lib.sha1 })
  }
  const index = path.join(CLIENT_HOME, 'assets/indexes', `${vanilla.assetIndex.id}.json`)
  await ensureFile(vanilla.assetIndex.url, index, { sha1: vanilla.assetIndex.sha1 })
  const objects = Object.values(readJson(index).objects)
  const missing = objects.filter((o) => !fs.existsSync(path.join(CLIENT_HOME, 'assets/objects', o.hash.slice(0, 2), o.hash)))
  if (missing.length) log(`  downloading ${missing.length} assets`)
  for (let i = 0; i < missing.length; i += 16) {
    await Promise.all(missing.slice(i, i + 16).map((o) => fetchFile(`https://resources.download.minecraft.net/${o.hash.slice(0, 2)}/${o.hash}`,
      path.join(CLIENT_HOME, 'assets/objects', o.hash.slice(0, 2), o.hash), { sha1: o.hash })))
  }
  fs.mkdirSync(path.join(CLIENT_HOME, 'remappedJars'), { recursive: true })
}

/**
 * Comando java del cliente de producción (ver installClient), como lo arma un launcher: el perfil
 * de Fabric hereda del de vanilla; sus librerías van por delante y luego el jar del cliente.
 */
function clientCommand (gameDir) {
  const vanilla = readJson(path.join(CLIENT_HOME, 'versions', MC, `${MC}.json`))
  const fabric = readJson(path.join(CLIENT_HOME, 'versions', FABRIC_PROFILE, `${FABRIC_PROFILE}.json`))
  const libraries = path.join(CLIENT_HOME, 'libraries')
  const classpath = []
  const seen = new Set()
  const add = (name, file) => {
    const [group, artifact, , classifier] = name.split(':')
    const key = `${group}:${artifact}:${classifier || ''}`
    if (seen.has(key)) return
    seen.add(key)
    classpath.push(file)
  }
  for (const lib of fabric.libraries) add(lib.name, path.join(libraries, mavenPath(lib.name)))
  for (const lib of vanilla.libraries) {
    const a = lib.downloads?.artifact
    if (a && allowed(lib.rules)) add(lib.name, path.join(libraries, a.path))
  }
  classpath.push(path.join(CLIENT_HOME, 'versions', MC, `${MC}.jar`))
  const natives = path.join(CLIENT_HOME, 'natives')
  fs.mkdirSync(natives, { recursive: true })
  const vars = {
    library_directory: libraries,
    classpath_separator: ':',
    classpath: classpath.join(':'),
    natives_directory: natives,
    launcher_name: 'directauth-modtests',
    launcher_version: '1',
    version_name: FABRIC_PROFILE,
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
  const expand = (args = []) => args.flatMap((arg) => typeof arg === 'string' ? [arg] : allowed(arg.rules) ? [arg.value].flat() : [])
    .map((arg) => arg.replace(/\$\{(\w+)\}/g, (m, name) => vars[name] ?? m))
  return {
    java: java21(),
    jvm: [...expand(vanilla.arguments.jvm), ...expand(fabric.arguments?.jvm)],
    main: [fabric.mainClass, ...expand(vanilla.arguments.game), ...expand(fabric.arguments?.game)]
  }
}

/** Comando java del servidor de producción (el lanzador de Fabric), desde el directorio del escenario. */
function serverCommand () {
  return { java: java21(), jvm: [], main: ['-jar', path.join(SERVER_HOME, SERVER_LAUNCHER)] }
}

// ---------- Directorios de juego ----------

function prepareDirs (scenario, mods, apiJar) {
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
  // Servidor de producción: el servidor vanilla (.fabric/server), sus librerías y las de Fabric
  // Loader son los de la instalación compartida, y Minecraft remapeado a intermediary también, en el
  // servidor y en el cliente (no se rehace cada vez)
  for (const d of ['libraries', 'versions']) fs.symlinkSync(path.join(SERVER_HOME, d), path.join(server, d))
  for (const [d, home, shared] of [[server, SERVER_HOME, ['server', 'remappedJars']], [client, CLIENT_HOME, ['remappedJars']]]) {
    fs.mkdirSync(path.join(d, '.fabric'))
    for (const s of shared) fs.symlinkSync(path.join(home, s), path.join(d, '.fabric', s))
  }
  const jar = directAuthJar()
  fs.copyFileSync(jar, path.join(server, 'mods', path.basename(jar)))
  for (const d of [server, client]) {
    fs.copyFileSync(HARNESS_JAR, path.join(d, 'mods', path.basename(HARNESS_JAR)))
    fs.copyFileSync(apiJar, path.join(d, 'mods', path.basename(apiJar)))
  }

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

  // Cliente: sin pausas al perder el foco, sin avisos de primera vez y con el mínimo de gráficos.
  // Sin el desenfoque del fondo de los menús: con OpenGL por software (Mesa) un fotograma de la
  // pantalla Destiny de MCA tardaba a veces más de 20 s y el arnés daba el cliente por colgado
  fs.writeFileSync(path.join(client, 'options.txt'), [
    'pauseOnLostFocus:false', 'onboardAccessibility:false', 'skipMultiplayerWarning:true',
    'joinedFirstServer:true', 'tutorialStep:none', 'narrator:0', 'renderDistance:2',
    'simulationDistance:5', 'maxFps:30', 'graphicsMode:0', 'renderClouds:"false"',
    'soundCategory_master:0.0', 'fullscreen:false', 'lang:en_us', 'menuBackgroundBlurriness:0'
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

async function runScenario (scenario, apiJar) {
  const def = MANIFEST.scenarios[scenario]
  if (!def) throw new Error(`unknown scenario ${scenario}`)
  const mods = modsOf(scenario)
  for (const mod of mods) await download(mod)
  const dirs = prepareDirs(scenario, mods, apiJar)
  const results = path.join(dirs.dir, 'results.jsonl')
  const serverLog = path.join(dirs.dir, 'server.log')
  const clientLog = path.join(dirs.dir, 'client.log')
  const started = Date.now()

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
  // Configuración de DirectAuth para la pasada (ver README): LOG_JAIL_PAYLOADS=1 registra en
  // server.log los payloads que recibe un jugador en la jaula (la pantalla de una mod nueva), y
  // DIRECTAUTH_CONFIG='{...}' cambia campos (p. ej. quitar un id por defecto para ver si hace falta)
  const config = {
    ...(process.env.LOG_JAIL_PAYLOADS === '1' ? { logJailPayloads: true } : {}),
    ...JSON.parse(process.env.DIRECTAUTH_CONFIG || '{}')
  }
  if (Object.keys(config).length) {
    const file = path.join(dirs.server, 'world/serverconfig/DirectAuth-config.json')
    fs.mkdirSync(path.dirname(file), { recursive: true })
    // Con otherModsDefaultsVersion al día, DirectAuth no vuelve a añadir los ids por defecto quitados
    fs.writeFileSync(file, JSON.stringify({ otherModsDefaultsVersion: 1000, ...config }, null, 2) + '\n')
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
  const ok = report(scenario, results, clientLog)
  log(`  ${scenario}: ${Math.round((Date.now() - started) / 1000)} s`)
  return ok
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
  for (const s of scenarios) if (!MANIFEST.scenarios[s]) throw new Error(`unknown scenario ${s}`)
  if (!eulaAccepted()) {
    log('Read the Minecraft EULA (https://aka.ms/MinecraftEULA) and accept it with:')
    log(`  mkdir -p '${RUNS}' && echo eula=true > '${path.join(RUNS, 'eula.txt')}'`)
    process.exit(1)
  }
  // DIRECTAUTH_JAR=<jar> prueba ese jar (p. ej. una release) en vez de compilar este checkout
  log('Building DirectAuth and the harness')
  if (!process.env.DIRECTAUTH_JAR) gradle(['remapJar'])
  await fetchAll()
  const apiJar = await fabricApi()
  await installServer()
  await installClient()
  // Las mods de build/mod-cache se remapean para compilar el arnés: va después de fetchAll
  gradle(['remapJar'], HERE)
  const failed = []
  for (const s of scenarios) {
    try {
      if (!await runScenario(s, apiJar)) failed.push(s)
    } catch (e) {
      log(`FAIL  ${s}: ${e.message}`)
      failed.push(s)
    }
  }
  log(failed.length ? `\nFAILED: ${failed.join(', ')}` : '\nALL PASSED')
  process.exit(failed.length ? 1 : 0)
})()
