// Pruebas que necesitan reiniciar el servidor. Uso: node restart.js <fase>
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const T = require('./lib')

const L = T.lang()
const PW = 'secret1'
const phase = process.argv[2]
const POS_FILE = path.join(T.CFG_DIR, 'DirectAuth_positions.json')
const CFG_FILE = path.join(T.CFG_DIR, 'DirectAuth-config.json')
const STATE = path.join(__dirname, 'restart-state.json')

async function joinOk (name, ip, opts) {
  const b = await T.join(name, ip, opts)
  T.assert(b.spawned, `${name} did not spawn (kick: ${b.kickReason})`)
  return b
}
async function register (name, ip) {
  const b = await joinOk(name, ip)
  await T.sleep(1100)
  T.send(b, `/register ${PW}`)
  await T.expect(b, L.msgRegistered)
  return b
}

;(async () => {
  if (phase === 'before-A') {
    // Jugador encerrado con posición guardada, otro autenticado, y un /login en vuelo al parar
    await T.test('before-A: set up players and stop with a /login in flight', async () => {
      let p = await register('Persist', '127.0.0.90')
      await T.rcon('tp Persist 20 -60 20')
      await T.sleep(500)
      T.send(p, '/logout')
      await T.expectKick(p, L.msgLogoutSuccess)
      p = await joinOk('Persist', '127.0.0.90')
      await T.expect(p, L.msgLoginRequest)
      await T.sleep(500)
      const pos = JSON.parse(fs.readFileSync(POS_FILE, 'utf8'))
      T.assert(pos[T.offlineUUID('Persist')], 'position not stored before stop')
      await register('Online1', '127.0.0.91')
      const inflight = await register('Inflight', '127.0.0.92')
      T.send(inflight, '/logout')
      await T.expectKick(inflight, L.msgLogoutSuccess)
      const i2 = await joinOk('Inflight', '127.0.0.92')
      await T.expect(i2, L.msgLoginRequest)
      fs.writeFileSync(STATE, JSON.stringify({ logOffset: T.serverLogSize() }))
      i2.chat(`/login ${PW}`)
      fs.writeFileSync(path.join(__dirname, 'console'), 'stop\n')
      await T.sleep(3000)
    })
  } else if (phase === 'after-A') {
    await T.test('after-A: clean shutdown log and stored position survived the restart', async () => {
      const { logOffset } = JSON.parse(fs.readFileSync(STATE, 'utf8'))
      const log = fs.readFileSync(path.join(__dirname, 'server-prev.out')).slice(logOffset).toString('utf8')
      const bad = log.split('\n').filter((l) => /Exception|\/ERROR|Caused by/.test(l) && !/DEBUG/.test(l))
      T.assert(bad.length === 0, 'errors during shutdown:\n' + bad.slice(0, 15).join('\n'))
      const start = log.match(/\[(\d\d:\d\d:\d\d)\].*Stopping the server/)
      const end = log.match(/\[(\d\d:\d\d:\d\d)\][^\n]*Unloading configs type SERVER/)
      console.log('      shutdown:', start && start[1], '->', end && end[1])
      const p = await joinOk('Persist', '127.0.0.90')
      await T.expect(p, L.msgLoginRequest)
      T.send(p, `/login ${PW}`)
      await T.expect(p, L.msgAuthenticated)
      await T.sleep(500)
      T.assert(T.near(await T.posOf('Persist'), [20.5, -60, 20.5]), `position lost across restart: ${await T.posOf('Persist')}`)
      const i = await joinOk('Inflight', '127.0.0.92')
      await T.expect(i, new RegExp(`${T.re(L.msgLoginRequest).source}|${T.re(L.msgSessionRestored).source}`))
    })
  } else if (phase === 'before-B') {
    // Ficheros rotos para el próximo arranque (servidor ya parado)
    fs.writeFileSync(POS_FILE, '{"broken": ')
    const good = fs.readFileSync(CFG_FILE, 'utf8')
    fs.writeFileSync(STATE, JSON.stringify({ goodConfig: good }))
    fs.writeFileSync(CFG_FILE, '{ "language": "es", "maxLoginAttempts": ')
    const salt = crypto.randomBytes(16)
    const hash = crypto.pbkdf2Sync('legacy9', salt, 10000, 32, 'sha256')
    const legacy = {
      jsonuser: { username: 'jsonuser', passwordHash: salt.toString('base64') + ':' + hash.toString('base64'), isPremium: false, onlineUUID: null },
      broken: null
    }
    fs.writeFileSync(path.join(T.CFG_DIR, 'DirectAuth_users.json'), JSON.stringify(legacy))
    console.log('prepared broken files')
  } else if (phase === 'after-B') {
    await T.test('after-B: corrupt positions moved aside, legacy JSON imported, malformed config untouched', async () => {
      const { goodConfig } = JSON.parse(fs.readFileSync(STATE, 'utf8'))
      const files = fs.readdirSync(T.CFG_DIR)
      T.assert(files.some((f) => f.startsWith('DirectAuth_positions.json.corrupt-')), `no .corrupt file: ${files}`)
      T.assert(files.includes('DirectAuth_users.json.MIGRATED') && !files.includes('DirectAuth_users.json'), `legacy JSON not migrated: ${files}`)
      T.assert(fs.readFileSync(CFG_FILE, 'utf8') === '{ "language": "es", "maxLoginAttempts": ', 'malformed config was overwritten')
      const b = await joinOk('JsonUser', '127.0.0.93')
      await T.expect(b, L.msgLoginRequest) // por defecto en inglés
      T.send(b, '/login legacy9')
      await T.expect(b, L.msgAuthenticated)
      await T.sleep(800)
      T.assert(T.user('JsonUser').passwordHash.split(':').length === 3, 'legacy hash not upgraded')
      fs.writeFileSync(CFG_FILE, goodConfig)
      const out = await T.rcon('directauth reload')
      T.assert(T.re(L.msgConfigReloaded).test(out), `reload after fixing: ${out}`)
    })
  } else if (phase === 'tr') {
    await T.test('turkish locale: legacy rows lowercased with the system locale are still found', async () => {
      const lc = await T.rcon('directauth reload')
      const salt = crypto.randomBytes(16)
      const hash = crypto.pbkdf2Sync('legacy7', salt, 100000, 32, 'sha256')
      // Fila como la habría escrito una versión antigua en un servidor turco: "IVAN".toLowerCase() = "ıvan"
      T.db('INSERT INTO users(username, passwordHash, isPremium) VALUES(?, ?, 0)', ['ıvan', salt.toString('base64') + ':' + hash.toString('base64')], { write: true })
      for (const name of ['IVAN', 'Ivan']) {
        const b = await joinOk(name, '127.0.0.97')
        await T.expect(b, L.msgLoginRequest)
        T.send(b, '/login legacy7')
        await T.expect(b, L.msgAuthenticated)
        await T.sleep(800)
        T.send(b, '/logout')
        await T.expectKick(b, L.msgLogoutSuccess)
      }
      const rows = T.db("SELECT username, passwordHash FROM users WHERE username IN ('ıvan', 'ivan')")
      T.assert(rows.length === 1 && rows[0].username === 'ıvan' && rows[0].passwordHash.split(':').length === 3, `rows: ${JSON.stringify(rows)}`)
      // Una cuenta nueva con 'I' se guarda con Locale.ROOT y se encuentra con cualquier mayúscula
      const g = await joinOk('IGOR', '127.0.0.98')
      await T.sleep(1100)
      T.send(g, `/register ${PW}`)
      await T.expect(g, L.msgRegistered)
      T.assert(T.user('igor'), 'IGOR not stored as "igor"')
      await T.quit(g)
      const g2 = await joinOk('igor', '127.0.0.99')
      await T.expect(g2, L.msgLoginRequest)
      await T.sleep(1100)
      T.send(g2, `/register ${PW}`)
      await T.expect(g2, L.errAlreadyRegistered)
    })
  }
  await T.closeRcon()
  process.exit(T.summary() ? 1 : 0)
})().catch((e) => { console.error(e); process.exit(2) })
