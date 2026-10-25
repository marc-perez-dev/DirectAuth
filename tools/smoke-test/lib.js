// Helpers para los smoke tests de DirectAuth: bots Mineflayer, RCON, SQLite y ficheros del mundo
const net = require('net')
const http = require('http')
const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const { Rcon } = require('rcon-client')
const { DatabaseSync } = require('node:sqlite')

// Directorio de juego propio del smoke test (ver server.sh)
const RUN = path.resolve(__dirname, '../../runs/smoke-test')
const WORLD = path.join(RUN, 'world')
const CFG_DIR = path.join(WORLD, 'serverconfig')
const CFG = path.join(CFG_DIR, 'DirectAuth-config.json')
const DB = path.join(CFG_DIR, 'directauth.db')
const SERVER_LOG = path.join(__dirname, 'server.out')

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const strip = (s) => String(s).replace(/§./g, '')

function lang () {
  return JSON.parse(fs.readFileSync(path.join(CFG_DIR, 'DirectAuth-lang-en.json'), 'utf8'))
}

/** Convierte un mensaje del fichero de idioma en una RegExp (sin colores, %d/%s comodín). */
function re (template) {
  const parts = strip(template).split(/%[ds]/).map((p) => p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  return new RegExp(parts.join('.*?'), 's')
}

function textOf (reason) {
  if (reason == null) return ''
  if (typeof reason === 'string') {
    try { return textOf(JSON.parse(reason)) } catch { return strip(reason) }
  }
  if (typeof reason === 'object') {
    // Componente de chat (JSON o NBT simplificado)
    if (reason.type && reason.value !== undefined) return textOf(reason.value)
    let out = ''
    if (reason.text !== undefined) out += textOf(reason.text)
    if (reason[''] !== undefined) out += textOf(reason[''])
    if (Array.isArray(reason)) return reason.map(textOf).join('')
    if (reason.extra) out += textOf(reason.extra)
    if (reason.translate) out += reason.translate
    return strip(out)
  }
  return strip(String(reason))
}

const bots = new Set()

/**
 * Conecta un bot. Resuelve cuando aparece en el mundo o cuando lo echan.
 * bot.msgs: mensajes recibidos (sin colores). bot.kickReason: texto del kick (o null).
 */
function join (username, ip = '127.0.0.1', { timeout = 20000, options = {} } = {}) {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({
      username,
      host: '127.0.0.1',
      port: 25565,
      version: '1.20.1',
      auth: 'offline',
      hideErrors: true,
      connect: (client) => client.setSocket(net.connect({ host: '127.0.0.1', port: 25565, localAddress: ip })),
      ...options
    })
    bot.ip = ip
    bot.logOffset = serverLogSize()
    bot.msgs = []
    bot.mark = 0
    bot.kickReason = null
    bot.connected = true
    bot.spawned = false
    bots.add(bot)
    bot.payloads = []
    bot.windows = []
    bot.playerInfo = []
    bot._client.on('custom_payload', (p) => {
      // SimpleChannel: el primer byte es el índice del mensaje (ver sendPayload)
      if (p.channel === TEST_CHANNEL) bot.payloads.push({ channel: `${p.channel}#${p.data[0]}`, value: readString(p.data.subarray(1)) })
      // Mensajes de Forge (fml:play#1: menú abierto con NetworkHooks.openScreen)
      if (p.channel === 'fml:play') bot.payloads.push({ channel: `${p.channel}#${p.data[0]}`, value: '' })
    })
    bot._client.on('open_window', (p) => bot.windows.push(p.windowId))
    bot._client.on('player_info', (p) => bot.playerInfo.push(p))
    bot.on('message', (m) => bot.msgs.push(strip(m.toString())))
    bot.on('kicked', (reason) => { bot.kickReason = textOf(reason) })
    // Se guardan para explicar una desconexión sin motivo (ver expectKick)
    bot.on('error', (e) => { bot.lastError = e })
    bot.on('end', (reason) => { bot.connected = false; bot.endReason = reason; bots.delete(bot) })
    const timer = setTimeout(() => reject(new Error(`${username}: no spawn in ${timeout}ms`)), timeout)
    bot.once('spawn', () => { bot.spawned = true; clearTimeout(timer); resolve(bot) })
    // Un jugador que entra muerto no recibe 'spawn' hasta reaparecer
    const onDead = () => { if (bot.spawned || bot.health > 0) return; bot.spawned = true; bot.joinedDead = true; clearTimeout(timer); resolve(bot) }
    bot.on('death', onDead)
    bot.on('health', onDead)
    bot._client.on('update_health', (p) => { if (p.health <= 0) { bot.health = 0; onDead() } })
    bot.once('end', () => { clearTimeout(timer); resolve(bot) })
  })
}

// --- Paquetes de mods (mod directauth_test, ver src/testmod) ---
// Los mensajes de prueba van por un SimpleChannel: índice del mensaje en el primer byte y un
// String con la codificación de FriendlyByteBuf.writeUtf. DirectAuth los llama "canal#índice".
const TEST_CHANNEL = 'directauth_test:main'
function writeString (s) {
  const body = Buffer.from(s, 'utf8')
  const len = []
  let n = body.length
  do { let b = n & 0x7f; n >>>= 7; if (n) b |= 0x80; len.push(b) } while (n)
  return Buffer.concat([Buffer.from(len), body])
}
function readString (buf) {
  let n = 0; let shift = 0; let i = 0
  for (;;) { const b = buf[i++]; n |= (b & 0x7f) << shift; if (!(b & 0x80)) break; shift += 7 }
  return buf.slice(i, i + n).toString('utf8')
}
/** Envía al servidor un payload de mod (como lo haría el cliente de otra mod), p. ej. 'directauth_test:main#0'. */
function sendPayload (bot, type, value) {
  const [channel, index] = type.split('#')
  // Architectury: el id del mensaje va dentro, como texto (architectury:network#directauth_test:x)
  const head = channel === 'architectury:network' ? writeString(index) : Buffer.from([Number(index)])
  bot._client.write('custom_payload', { channel, data: Buffer.concat([head, writeString(value)]) })
}
/** Valores recibidos de un tipo de prueba (p. ej. 'directauth_test:main#2') o de Forge ('fml:play#1'). */
const received = (bot, channel) => bot.payloads.filter((p) => p.channel === channel).map((p) => p.value)
/** Lo que el mod de pruebas anotó para un jugador: ['action:a1', 'hello:x', 'button:tag:3', ...] */
async function datestLog (name) {
  const r = await rcon(`datest log ${name}`)
  const m = r.match(/\[(.*)\]\s*$/s)
  return m && m[1] ? m[1].split(',') : []
}

/** Envía un comando o mensaje y marca el punto desde el que se esperan respuestas. */
function send (bot, text) {
  bot.mark = bot.msgs.length
  bot.chat(text)
}

async function expect (bot, pattern, timeout = 6000) {
  const rx = pattern instanceof RegExp ? pattern : re(pattern)
  const start = Date.now()
  while (Date.now() - start < timeout) {
    const found = bot.msgs.slice(bot.mark).find((m) => rx.test(m))
    if (found) return found
    if (!bot.connected) break
    await sleep(50)
  }
  throw new Error(`${bot.username}: expected message ${rx} — got ${JSON.stringify(bot.msgs.slice(bot.mark))}${bot.kickReason ? ' kicked: ' + bot.kickReason : ''}`)
}

/** Comprueba que NO llega un mensaje que case durante `ms`. */
async function expectNo (bot, pattern, ms = 1500) {
  const rx = pattern instanceof RegExp ? pattern : re(pattern)
  await sleep(ms)
  const found = bot.msgs.slice(bot.mark).find((m) => rx.test(m))
  if (found) throw new Error(`${bot.username}: unexpected message "${found}"`)
}

async function expectKick (bot, pattern, timeout = 6000) {
  const rx = pattern instanceof RegExp ? pattern : re(pattern)
  const start = Date.now()
  while (bot.connected && Date.now() - start < timeout) await sleep(50)
  if (bot.connected) throw new Error(`${bot.username}: expected kick ${rx}, still connected`)
  // Si el servidor cierra mientras el bot aún le envía paquetes (un kick justo al entrar), el
  // cierre llega como reset y Linux descarta lo recibido sin leer, el paquete del kick incluido:
  // entonces cuenta el motivo con el que el servidor lo echó, según su log
  if (!bot.kickReason && ['ECONNRESET', 'EPIPE'].includes(bot.lastError?.code)) bot.kickReason = await kickReasonInLog(bot)
  if (!rx.test(bot.kickReason || '')) throw new Error(`${bot.username}: kick reason "${bot.kickReason}" does not match ${rx} (end: ${bot.endReason}, error: ${bot.lastError})`)
  return bot.kickReason
}

async function kickReasonInLog (bot) {
  const name = bot.username.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  // "<nombre> lost connection: …" en juego; en el login, el GameProfile (…name=<nombre>,…) y la IP.
  // El nombre exacto: Notch no debe tomar la línea de Notch#
  const rx = new RegExp(`\\]: (?:${name}|[^\\n]*?name=${name},[^\\n]*?) lost connection: ([\\s\\S]*?)(?=\\n\\[|$)`, 'g')
  for (let i = 0; i < 20; i++) {
    // Si el servidor se reinició después de entrar el bot, el log empieza de nuevo
    const found = [...serverLogSince(bot.logOffset > serverLogSize() ? 0 : bot.logOffset).matchAll(rx)].pop()
    if (found) return found[1].trim()
    await sleep(100)
  }
  return null
}

async function expectConnected (bot, ms = 1000) {
  await sleep(ms)
  if (!bot.connected) throw new Error(`${bot.username}: was disconnected (${bot.kickReason})`)
}

async function quit (bot) {
  if (!bot || !bot.connected) return
  const ended = new Promise((r) => bot.once('end', r))
  bot.quit()
  await Promise.race([ended, sleep(3000)])
}

/** Espera a que el servidor procese la salida (y guarde los datos del jugador): ya no sale en /list. */
async function waitGone (name) {
  const online = async () => (await rcon('list')).replace(/^[^:]*:/, '').split(',').map((n) => n.trim())
  for (let i = 0; i < 50 && (await online()).includes(name); i++) await sleep(100)
}

async function quitAll () {
  await Promise.all([...bots].map(quit))
  await sleep(300)
}

// --- RCON ---
let rconClient = null
async function rcon (cmd) {
  if (!rconClient) {
    rconClient = await Rcon.connect({ host: '127.0.0.1', port: 25575, password: 'smoketest' })
  }
  return strip(await rconClient.send(cmd))
}
async function closeRcon () { if (rconClient) await rconClient.end().catch(() => {}); rconClient = null }

// --- Servidor de sesión de Mojang falso (server.sh le pasa al servidor minecraft.api.session.host) ---
// Responde a hasJoined como Mojang para los nombres dados con premium(): así un bot sin cuenta
// entra como premium. El resto recibe 204, como un cliente que no ha entrado con su cuenta.
const SESSION_PORT = 25580
const premiumProfiles = new Map()
let sessionServer = null
/** El próximo login de {@code name} lo confirma Mojang con esta UUID. Devuelve la skin que llevará. */
async function premium (name, id) {
  const textures = Buffer.from(JSON.stringify({ timestamp: Date.now(), profileId: id.replace(/-/g, ''), profileName: name, textures: {} })).toString('base64')
  premiumProfiles.set(name.toLowerCase(), { id: id.replace(/-/g, ''), name, properties: [{ name: 'textures', value: textures }] })
  if (!sessionServer) {
    const server = http.createServer((req, res) => {
      const url = new URL(req.url, 'http://localhost')
      const profile = url.pathname === '/session/minecraft/hasJoined' && premiumProfiles.get(String(url.searchParams.get('username')).toLowerCase())
      res.writeHead(profile ? 200 : 204, { 'content-type': 'application/json' })
      res.end(profile ? JSON.stringify(profile) : undefined)
    })
    // Solo se guarda si escucha: si el puerto está ocupado, cada premium() vuelve a fallar con el error
    await new Promise((resolve, reject) => server.once('error', reject).listen(SESSION_PORT, '127.0.0.1', resolve))
    server.unref()
    sessionServer = server
  }
  return textures
}
const notPremium = (name) => premiumProfiles.delete(name.toLowerCase())

// --- SQLite (solo lectura salvo que se pida) ---
function db (sql, params = [], { write = false } = {}) {
  const conn = new DatabaseSync(DB, { readOnly: !write })
  try {
    const stmt = conn.prepare(sql)
    return write ? stmt.run(...params) : stmt.all(...params)
  } finally {
    conn.close()
  }
}
const user = (name) => db('SELECT * FROM users WHERE username = ?', [name.toLowerCase()])[0]

// --- Config ---
function readConfig () { return JSON.parse(fs.readFileSync(CFG, 'utf8')) }
async function setConfig (patch) {
  const cfg = { ...readConfig(), ...patch }
  fs.writeFileSync(CFG, JSON.stringify(cfg, null, 2))
  const out = await rcon('directauth reload')
  return out
}

// UUID offline como la calcula vanilla (MD5 de "OfflinePlayer:<nombre>", versión 3)
function offlineUUID (name) {
  const md5 = require('crypto').createHash('md5').update('OfflinePlayer:' + name, 'utf8').digest()
  md5[6] = (md5[6] & 0x0f) | 0x30
  md5[8] = (md5[8] & 0x3f) | 0x80
  const h = md5.toString('hex')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

function serverLogSince (offset) {
  const buf = fs.readFileSync(SERVER_LOG)
  return buf.slice(offset).toString('utf8')
}
const serverLogSize = () => fs.statSync(SERVER_LOG).size

// --- Runner ---
const results = []
// Una prueba atascada (un bot que espera algo que no llega) falla en vez de colgar la batería
const TEST_TIMEOUT = 180000
async function test (name, fn) {
  const t0 = Date.now()
  let timer
  try {
    await Promise.race([fn(), new Promise((resolve, reject) => {
      timer = setTimeout(() => reject(new Error(`timed out after ${TEST_TIMEOUT / 1000}s`)), TEST_TIMEOUT)
    })])
    results.push({ name, ok: true })
    console.log(`PASS  ${name} (${Date.now() - t0}ms)`)
  } catch (e) {
    results.push({ name, ok: false, error: e.message })
    console.log(`FAIL  ${name}: ${e.message}`)
  } finally {
    clearTimeout(timer)
    await quitAll()
  }
}
function summary () {
  const failed = results.filter((r) => !r.ok)
  console.log(`\n${results.length - failed.length}/${results.length} passed`)
  return failed.length
}

function assert (cond, msg) { if (!cond) throw new Error(msg) }

// --- Estado de jugadores vía RCON ---
async function posOf (name) {
  const r = await rcon(`data get entity ${name} Pos`)
  const m = r.match(/\[(-?[\d.E-]+)d, (-?[\d.E-]+)d, (-?[\d.E-]+)d\]/)
  return m ? m.slice(1).map(Number) : null
}
async function dimOf (name) {
  const r = await rcon(`data get entity ${name} Dimension`)
  const m = r.match(/"([^"]+)"/)
  return m ? m[1] : r
}
async function countItem (name, item) {
  const r = await rcon(`clear ${name} ${item} 0`)
  const m = r.match(/Found (\d+)/)
  return m ? Number(m[1]) : 0
}
/** Verifica un hash como lo hacen DirectAuth 1.1/1.2 (salt:hash, 100k iteraciones): si pasa, volver a esas versiones no deja al jugador fuera. */
function verifiesLikeOldVersions (storedHash, password) {
  const parts = String(storedHash).split(':')
  if (parts.length !== 2) return false
  const expected = Buffer.from(parts[1], 'base64')
  const actual = require('crypto').pbkdf2Sync(password, Buffer.from(parts[0], 'base64'), 100000, 32, 'sha256')
  return expected.length === actual.length && expected.equals(actual)
}

function schemaVersion () {
  return db('PRAGMA user_version')[0].user_version
}
const dbBackups = () => fs.readdirSync(CFG_DIR).filter((f) => /^directauth-backup-.*\.db$/.test(f))

function near (a, b, tol = 1.5) {
  return a && b && Math.abs(a[0] - b[0]) <= tol && Math.abs(a[1] - b[1]) <= tol && Math.abs(a[2] - b[2]) <= tol
}

module.exports = {
  RUN, WORLD, CFG_DIR, sleep, strip, lang, re, join, send, expect, expectNo, expectKick, expectConnected,
  quit, quitAll, waitGone, rcon, closeRcon, db, user, readConfig, setConfig, offlineUUID, serverLogSince, serverLogSize,
  test, summary, assert, posOf, dimOf, countItem, near, verifiesLikeOldVersions, schemaVersion, dbBackups,
  sendPayload, received, datestLog, premium, notPremium
}
