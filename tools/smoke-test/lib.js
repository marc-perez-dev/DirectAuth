// Helpers para los smoke tests de DirectAuth: bots Mineflayer, RCON, SQLite y ficheros del mundo
const net = require('net')
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
    bot.msgs = []
    bot.mark = 0
    bot.kickReason = null
    bot.connected = true
    bot.spawned = false
    bots.add(bot)
    bot.on('message', (m) => bot.msgs.push(strip(m.toString())))
    bot.on('kicked', (reason) => { bot.kickReason = textOf(reason) })
    bot.on('error', () => {})
    bot.on('end', () => { bot.connected = false; bots.delete(bot) })
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
  if (!rx.test(bot.kickReason || '')) throw new Error(`${bot.username}: kick reason "${bot.kickReason}" does not match ${rx}`)
  return bot.kickReason
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
async function test (name, fn) {
  const t0 = Date.now()
  try {
    await fn()
    results.push({ name, ok: true })
    console.log(`PASS  ${name} (${Date.now() - t0}ms)`)
  } catch (e) {
    results.push({ name, ok: false, error: e.message })
    console.log(`FAIL  ${name}: ${e.message}`)
  } finally {
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
  quit, quitAll, rcon, closeRcon, db, user, readConfig, setConfig, offlineUUID, serverLogSince, serverLogSize,
  test, summary, assert, posOf, dimOf, countItem, near, verifiesLikeOldVersions, schemaVersion, dbBackups
}
