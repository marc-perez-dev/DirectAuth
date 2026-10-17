// Smoke tests de DirectAuth contra un servidor de desarrollo real. Uso: node tests.js [filtro]
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const Vec3 = require('vec3')
const T = require('./lib')

const L = T.lang()
const PW = 'secret1'
const SPAWN = [0.5, -60, 0.5]
const filter = process.argv[2] ? new RegExp(process.argv[2], 'i') : null
const test = (name, fn) => (!filter || filter.test(name)) ? T.test(name, fn) : Promise.resolve()

// --- Helpers ---
async function joinOk (name, ip, opts) {
  const b = await T.join(name, ip, opts)
  T.assert(b.spawned, `${name} did not spawn (kick: ${b.kickReason})`)
  return b
}
async function register (name, ip, pw = PW, opts) {
  const b = await joinOk(name, ip, opts)
  await T.expect(b, L.msgWelcome)
  await T.sleep(1100) // registrationDelay
  T.send(b, `/register ${pw}`)
  await T.expect(b, L.msgRegistered)
  return b
}
async function login (name, ip, pw = PW) {
  const b = await joinOk(name, ip)
  await T.expect(b, new RegExp(`${T.re(L.msgLoginRequest).source}|${T.re(L.msgSessionRestored).source}`))
  if (b.msgs.some((m) => T.re(L.msgSessionRestored).test(m))) return b
  T.send(b, `/login ${pw}`)
  await T.expect(b, L.msgAuthenticated)
  return b
}
async function logout (b) {
  T.send(b, '/logout')
  await T.expectKick(b, L.msgLogoutSuccess)
}
let adminRegistered = false
async function admin () {
  const b = adminRegistered ? await login('Admin', '127.0.0.60') : await register('Admin', '127.0.0.60')
  if (!adminRegistered) { await T.rcon('op Admin'); adminRegistered = true }
  return b
}
async function adminCmd (cmd, pattern, timeout) {
  const a = await admin()
  T.send(a, cmd)
  return T.expect(a, pattern, timeout)
}
const exists = (rel) => fs.existsSync(path.join(T.WORLD, rel))
const rconTest = async (cmd) => /Test passed/.test(await T.rcon(cmd))

;(async () => {
  await T.setConfig({ loginCooldownMs: 1000 })

  // ========== Registro ==========
  await test('register: delay, length bounds, success, hash format', async () => {
    const b = await joinOk('RegA', '127.0.0.3')
    await T.expect(b, L.msgWelcome)
    T.send(b, `/register ${PW}`)
    await T.expect(b, L.errRegistrationCooldown)
    await T.sleep(1100)
    T.send(b, '/register abc')
    T.assert(/at least 4 /.test(await T.expect(b, L.errPasswordTooShort)), 'min length not formatted')
    T.send(b, '/register ' + 'x'.repeat(40))
    T.assert(/longer than 32 /.test(await T.expect(b, L.errPasswordTooLong)), 'max length not formatted')
    T.send(b, `/register ${PW}`)
    await T.expect(b, L.msgRegistered)
    const u = T.user('RegA')
    T.assert(u && T.verifiesLikeOldVersions(u.passwordHash, PW), `hash not readable by 1.1/1.2: ${u && u.passwordHash}`)
    T.assert(u.registrationIp === '127.0.0.3', `bad ip ${u.registrationIp}`)
    T.send(b, `/register ${PW}`)
    await T.expect(b, L.errAlreadyAuthenticated)
  })

  await test('register: second /register in the same tick is rejected while the first runs', async () => {
    const b = await joinOk('RegDbl', '127.0.0.4')
    await T.sleep(1100)
    b.mark = b.msgs.length
    b.chat(`/register ${PW}`)
    b.chat(`/register ${PW}`)
    await T.expect(b, L.errOperationPending)
    await T.expect(b, L.msgRegistered)
    T.assert(T.db("SELECT COUNT(*) c FROM users WHERE username = 'regdbl'")[0].c === 1, 'expected exactly one row')
  })

  await test('register: maxAccountsPerIP is atomic under concurrent registrations', async () => {
    await T.setConfig({ maxAccountsPerIP: 1 })
    try {
      const [a, b] = await Promise.all([joinOk('RaceA', '127.0.0.5'), joinOk('RaceB', '127.0.0.5')])
      await T.sleep(1100)
      a.mark = a.msgs.length; b.mark = b.msgs.length
      a.chat(`/register ${PW}`); b.chat(`/register ${PW}`)
      const any = new RegExp(`${T.re(L.msgRegistered).source}|${T.re(L.errIpLimitReached).source}`)
      const ra = await T.expect(a, any); const rb = await T.expect(b, any)
      const ok = [ra, rb].filter((m) => T.re(L.msgRegistered).test(m)).length
      T.assert(ok === 1, `expected exactly one registration, got ${ok}: ${ra} / ${rb}`)
      T.assert(T.db("SELECT COUNT(*) c FROM users WHERE registrationIp = '127.0.0.5'")[0].c === 1, 'expected one row for the IP')
    } finally {
      await T.setConfig({ maxAccountsPerIP: 5 })
    }
  })

  // ========== Login ==========
  await test('login: wrong password counter, cooldown, success restores position', async () => {
    let b = await register('Pos', '127.0.0.6')
    await T.rcon('tp Pos 40 -60 40')
    await T.sleep(500)
    await logout(b)
    b = await joinOk('Pos', '127.0.0.6')
    await T.expect(b, L.msgLoginRequest)
    T.assert(T.near(await T.posOf('Pos'), SPAWN), `not moved to spawn: ${await T.posOf('Pos')}`)
    T.send(b, '/login wrongpw')
    T.assert(/\(1\/5/.test(await T.expect(b, L.errWrongPassword)), 'counter not 1/5')
    T.send(b, `/login ${PW}`)
    await T.expect(b, L.errCooldown)
    await T.sleep(1100)
    T.send(b, `/login ${PW}`)
    await T.expect(b, L.msgAuthenticated)
    await T.sleep(300)
    T.assert(T.near(await T.posOf('Pos'), [40.5, -60, 40.5]), `position not restored: ${await T.posOf('Pos')}`)
    T.send(b, '/login ' + PW)
    await T.expect(b, L.errAlreadyAuthenticated)
  })

  await test('login: unregistered player gets errNotRegistered', async () => {
    const b = await joinOk('Nobody', '127.0.0.7')
    await T.expect(b, L.msgWelcome)
    T.send(b, `/login ${PW}`)
    await T.expect(b, L.errNotRegistered)
  })

  // ========== Jaula ==========
  await test('jail: chat, commands, movement, Q-drop, container click, creative slot, block break, damage, effects, armor stand', async () => {
    const JAIL = process.env.JAIL || 'Jail'
    let b = await register(JAIL, process.env.JAIL_IP || '127.0.0.8')
    await T.rcon(`give ${JAIL} minecraft:diamond 5`)
    await T.rcon(`give ${JAIL} minecraft:iron_helmet 1`)
    await T.sleep(300)
    await logout(b)
    const obs = await register(JAIL + 'O', '127.0.0.9')
    b = await joinOk(JAIL, process.env.JAIL_IP || '127.0.0.8')
    await T.expect(b, L.msgLoginRequest)
    await T.sleep(500)

    // Chat: bloqueado y no llega a los demás
    obs.mark = obs.msgs.length
    T.send(b, 'hello_secret_chat')
    await T.expect(b, L.msgUseCommands)
    await T.expectNo(obs, /hello_secret_chat/, 800)

    // Comandos fuera de la lista blanca
    T.send(b, '/me hi')
    await T.expect(b, L.msgUseCommands)
    T.send(b, '/directauth confirm')
    await T.expect(b, L.msgUseCommands)

    // Movimiento
    const start = await T.posOf(JAIL)
    b.setControlState('forward', true); b.setControlState('sprint', true)
    await T.sleep(1500)
    b.clearControlStates()
    await T.sleep(500)
    T.assert(T.near(await T.posOf(JAIL), start, 1.6), `moved from ${start} to ${await T.posOf(JAIL)}`)

    // Q (soltar el stack de la mano)
    await T.rcon('kill @e[type=item]')
    const diamond = b.inventory.items().find((i) => i.name === 'diamond')
    T.assert(diamond, 'no diamond in client inventory')
    b.setQuickBarSlot(diamond.slot - 36)
    await T.sleep(200)
    b.mark = b.msgs.length
    b._client.write('block_dig', { status: 3, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
    await T.expect(b, L.msgNoDrop)
    await T.sleep(500)
    T.assert(await T.countItem(JAIL, 'minecraft:diamond') === 5, 'diamonds dropped with Q')
    T.assert(!(await rconTest('execute if entity @e[type=item]')), 'item entity exists after Q')

    // Clic en el inventario (tirar fuera de la ventana)
    await Promise.race([b.tossStack(b.inventory.items().find((i) => i.name === 'diamond')).catch(() => {}), T.sleep(2000)])
    await T.sleep(500)
    T.assert(await T.countItem(JAIL, 'minecraft:diamond') === 5, 'diamonds dropped via container click')
    T.assert(!(await rconTest('execute if entity @e[type=item]')), 'item entity exists after container click')

    // Ranura de creativo
    await T.rcon(`gamemode creative ${JAIL}`)
    await T.sleep(300)
    const Item = require('prismarine-item')('1.20.1')
    const mcData = require('minecraft-data')('1.20.1')
    b._client.write('set_creative_slot', { slot: 40, item: Item.toNotch(new Item(mcData.itemsByName.diamond_block.id, 64)) })
    await T.sleep(500)
    T.assert(await T.countItem(JAIL, 'minecraft:diamond_block') === 0, 'creative slot packet gave items')
    await T.rcon(`gamemode survival ${JAIL}`)

    // Romper bloque
    const p = (await T.posOf(JAIL)).map(Math.floor)
    await T.rcon(`setblock ${p[0]} ${p[1] - 1} ${p[2]} minecraft:grass_block`)
    await T.sleep(300)
    const below = b.blockAt(new Vec3(p[0], p[1] - 1, p[2]))
    await Promise.race([b.dig(below).catch(() => {}), T.sleep(2500)])
    b.stopDigging && b.stopDigging()
    T.assert(await rconTest(`execute if block ${p[0]} ${p[1] - 1} ${p[2]} minecraft:grass_block`), 'block was broken')

    // Daño
    await T.rcon(`damage ${JAIL} 6`)
    await T.sleep(300)
    T.assert(b.health === 20, `took damage: ${b.health}`)

    // Efectos congelados
    await T.rcon(`effect give ${JAIL} minecraft:speed 30`)
    await T.sleep(2500)
    const dur1 = Number((await T.rcon(`data get entity ${JAIL} ActiveEffects[0].Duration`)).match(/(\d+)\s*$/)?.[1])
    T.assert(dur1 >= 595, `effect duration ticked down while jailed: ${dur1}`)

    // Armor stand (INTERACT_AT) con el casco en la mano
    await T.rcon(`summon minecraft:armor_stand ${p[0] + 1.5} ${p[1]} ${p[2] + 0.5}`)
    await T.sleep(500)
    const helmet = b.inventory.items().find((i) => i.name === 'iron_helmet')
    b.setQuickBarSlot(helmet.slot - 36)
    await T.sleep(200)
    const stand = Object.values(b.entities).find((e) => e.name === 'armor_stand')
    T.assert(stand, 'armor stand not visible')
    await b.activateEntityAt(stand, stand.position.offset(0, 1.8, 0)).catch(() => {})
    await b.activateEntity(stand).catch(() => {})
    await T.sleep(500)
    T.assert(await T.countItem(JAIL, 'minecraft:iron_helmet') === 1, 'helmet left the inventory')
    T.assert(!(await rconTest('execute if entity @e[type=armor_stand,nbt={ArmorItems:[{},{},{},{id:"minecraft:iron_helmet"}]}]')), 'armor stand got the helmet')
    await T.rcon('kill @e[type=armor_stand]')

    // Tras el login todo vuelve a funcionar y el efecto vuelve a correr
    T.send(b, `/login ${PW}`)
    await T.expect(b, L.msgAuthenticated)
    await T.sleep(2000)
    const dur2 = Number((await T.rcon(`data get entity ${JAIL} ActiveEffects[0].Duration`)).match(/(\d+)\s*$/)?.[1])
    T.assert(dur2 < dur1 - 20, `effect not ticking after login: ${dur1} -> ${dur2}`)
    obs.mark = obs.msgs.length
    T.send(b, 'hello_after_login')
    await T.expect(obs, /hello_after_login/)

    // Controles positivos: tras el login sí se puede tirar y romper
    await T.sleep(500)
    const d2 = b.inventory.items().find((i) => i.name === 'diamond')
    b.setQuickBarSlot(d2.slot - 36)
    await T.sleep(200)
    b._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
    await T.sleep(500)
    T.assert(await T.countItem(JAIL, 'minecraft:diamond') === 4, 'control: Q did not drop after login')
    await Promise.race([b.tossStack(b.inventory.items().find((i) => i.name === 'diamond')).catch(() => {}), T.sleep(2000)])
    await T.sleep(500)
    T.assert(await T.countItem(JAIL, 'minecraft:diamond') === 0, 'control: tossStack did not drop after login')
    await T.sleep(1000)
    const q = [b.entity.position.x, b.entity.position.y, b.entity.position.z].map(Math.floor)
    const under = b.blockAt(new Vec3(q[0] + 1, q[1] - 1, q[2]))
    T.assert(under && under.name === 'grass_block', `control: unexpected block ${under && under.name}`)
    const t0 = Date.now()
    let dug = false
    await Promise.race([b.dig(under).then(() => { dug = true }).catch((e) => console.log('      control dig error:', e.message)), T.sleep(10000)])
    await T.sleep(300)
    if (!dug) console.log('      control dig did not finish in', Date.now() - t0, 'ms; onGround', b.entity.onGround, 'pos', b.entity.position)
    T.assert(!(await rconTest(`execute if block ${q[0] + 1} ${q[1] - 1} ${q[2]} minecraft:grass_block`)), 'control: could not break a block after login')
  })

  await test('jail: login timeout kicks', async () => {
    await T.setConfig({ loginTimeout: 3 })
    try {
      const b = await joinOk('Slow', '127.0.0.10')
      await T.expectKick(b, L.msgTimeout, 8000)
    } finally {
      await T.setConfig({ loginTimeout: 60 })
    }
  })

  const DEAD = process.env.DEAD || 'Dead'
  await test('respawn while jailed goes to world spawn, keeps anchor charge; /login uses the anchor', async () => {
    const b = await register(DEAD, process.env.DEAD_IP || '127.0.0.12', PW, { options: { respawn: false } })
    // Plataforma con un nexo cargado en el Nether
    // Mantener cargado el chunk del nexo para poder comprobarlo aunque no haya nadie en el Nether
    await T.rcon('execute in minecraft:the_nether run forceload add 0 0 15 15')
    // Primero llevar al jugador (si no, el chunk no está cargado y fill/setblock fallan)
    await T.rcon(`execute in minecraft:the_nether run tp ${DEAD} 10.5 70 12.5`)
    for (let i = 0; i < 100 && !(/nether/.test(b.game.dimension) && b.blockAt(new Vec3(10, 69, 10))); i++) await T.sleep(100)
    await T.sleep(500)
    for (let i = 0; i < 100 && !/Successfully/.test(await T.rcon('execute in minecraft:the_nether run fill 6 69 6 14 75 14 minecraft:air')); i++) await T.sleep(100)
    await T.rcon('execute in minecraft:the_nether run fill 6 69 6 14 69 14 minecraft:obsidian')
    await T.rcon('execute in minecraft:the_nether run setblock 10 70 10 minecraft:respawn_anchor[charges=2]')
    T.assert(await rconTest('execute in minecraft:the_nether if block 10 70 10 minecraft:respawn_anchor[charges=2]'), 'anchor setup failed')
    // Salir y volver a entrar al Nether para que el bot reciba el chunk ya construido
    await T.rcon(`execute in minecraft:overworld run tp ${DEAD} 0.5 -60 0.5`)
    for (let i = 0; i < 50 && !/overworld/.test(b.game.dimension); i++) await T.sleep(100)
    await T.sleep(500)
    await T.rcon(`execute in minecraft:the_nether run tp ${DEAD} 10.5 70 12.5`)
    let anchor = null
    for (let i = 0; i < 100 && !(anchor && anchor.name === 'respawn_anchor'); i++) { await T.sleep(100); anchor = b.blockAt(new Vec3(10, 70, 10)) }
    T.assert(anchor && anchor.name === 'respawn_anchor', `anchor not visible: ${anchor && anchor.name}`)
    await b.activateBlock(anchor)
    await T.sleep(800)
    const spawnDim = await T.rcon(`data get entity ${DEAD} respawn`)
    const spawnDim2 = await T.rcon(`data get entity ${DEAD} SpawnDimension`)
    T.assert(/the_nether/.test(spawnDim + spawnDim2), `spawn not set at the anchor: ${spawnDim} ${spawnDim2}`)
    // Morir en otro sitio del Nether y salir sin reaparecer
    await T.rcon(`execute in minecraft:the_nether run tp ${DEAD} 13.5 70 13.5`)
    await T.sleep(500)
    await T.rcon(`kill ${DEAD}`)
    await T.sleep(800)
    T.assert(b.health === 0, `bot not dead: ${b.health}`)
    T.assert(await rconTest('execute in minecraft:the_nether if block 10 70 10 minecraft:respawn_anchor[charges=2]'), 'anchor charge changed before rejoin')
    await T.quit(b)
    // Volver desde otra IP (sin sesión de gracia), muerto
    const d = await joinOk(DEAD, process.env.DEAD_IP2 || '127.0.0.13', { options: { respawn: false } })
    await T.expect(d, L.msgLoginRequest)
    T.assert(d.joinedDead, 'did not join dead')
    T.assert(await rconTest('execute in minecraft:the_nether if block 10 70 10 minecraft:respawn_anchor[charges=2]'), 'anchor charge changed on join')
    d.respawn()
    await T.sleep(2500)
    T.assert((await T.dimOf(DEAD)) === 'minecraft:overworld', `respawned in ${await T.dimOf(DEAD)}`)
    T.assert(T.near(await T.posOf(DEAD), SPAWN, 2), `respawned at ${await T.posOf(DEAD)}`)
    T.assert(await rconTest('execute in minecraft:the_nether if block 10 70 10 minecraft:respawn_anchor[charges=2]'), 'anchor charge consumed while jailed')
    T.send(d, `/login ${PW}`)
    await T.expect(d, L.msgAuthenticated)
    await T.sleep(1500)
    T.assert((await T.dimOf(DEAD)) === 'minecraft:the_nether', `after login in ${await T.dimOf(DEAD)}`)
    T.assert(T.near(await T.posOf(DEAD), [10.5, 70, 10.5], 2), `after login at ${await T.posOf(DEAD)}`)
    T.assert(await rconTest('execute in minecraft:the_nether if block 10 70 10 minecraft:respawn_anchor[charges=1]'), 'anchor charge not consumed by /login respawn')
  })

  await test('/login without a respawn point: world spawn, out of blocks built over it', async () => {
    const NOBED = process.env.NOBED || 'NoBed'
    const b = await register(NOBED, '127.0.0.14', PW, { options: { respawn: false } })
    await T.rcon(`kill ${NOBED}`)
    await T.sleep(800)
    T.assert(b.health === 0, `bot not dead: ${b.health}`)
    await T.quit(b)
    // Bloques encima del spawn del mundo: teletransportar a sus coordenadas exactas lo dejaría dentro
    await T.rcon('fill -3 -60 -3 3 -57 3 minecraft:stone')
    try {
      const d = await joinOk(NOBED, '127.0.0.15', { options: { respawn: false } })
      T.assert(d.joinedDead, 'did not join dead')
      d.respawn()
      await T.sleep(2000)
      T.send(d, `/login ${PW}`)
      await T.expect(d, L.msgAuthenticated)
      await T.sleep(1500)
      T.assert((await T.dimOf(NOBED)) === 'minecraft:overworld', `after login in ${await T.dimOf(NOBED)}`)
      T.assert(await rconTest(`execute at ${NOBED} if block ~ ~ ~ minecraft:air`) && await rconTest(`execute at ${NOBED} if block ~ ~1 ~ minecraft:air`),
        `placed inside blocks at ${await T.posOf(NOBED)}`)
    } finally {
      await T.rcon('fill -3 -60 -3 3 -57 3 minecraft:air')
    }
  })

  // ========== Bloqueo por intentos ==========
  await test('lockout: survives reconnect and case variants, other IP unaffected, resetpass clears it', async () => {
    await T.setConfig({ loginCooldownMs: 200 })
    try {
      let b = await register('Lock', '127.0.0.20')
      await logout(b)
      b = await joinOk('Lock', '127.0.0.20')
      for (let i = 1; i <= 4; i++) {
        T.send(b, '/login bad' + i)
        await T.expect(b, new RegExp(`\\(${i}/5`))
        await T.sleep(250)
      }
      T.send(b, '/login bad5')
      await T.expectKick(b, L.errMaxAttempts)
      b = await joinOk('Lock', '127.0.0.20')
      T.send(b, `/login ${PW}`)
      await T.expectKick(b, L.errMaxAttempts)
      b = await joinOk('LOCK', '127.0.0.20')
      T.send(b, `/login ${PW}`)
      await T.expectKick(b, L.errMaxAttempts)
      b = await joinOk('Lock', '127.0.0.21')
      T.send(b, `/login ${PW}`)
      await T.expect(b, L.msgAuthenticated)
      await T.quit(b)
      await adminCmd('/directauth resetpass Lock newpass1', T.re(L.msgAdminResetSuccess))
      b = await joinOk('Lock', '127.0.0.20')
      T.send(b, '/login newpass1')
      await T.expect(b, L.msgAuthenticated)
    } finally {
      await T.setConfig({ loginCooldownMs: 1000 })
    }
  })

  await test('lockout: failures accumulate across reconnects and expire after loginLockoutSeconds', async () => {
    await T.setConfig({ loginCooldownMs: 200, loginLockoutSeconds: 3 })
    try {
      let b = await register('Lock2', '127.0.0.22')
      await logout(b)
      b = await joinOk('Lock2', '127.0.0.22')
      T.send(b, '/login bad1'); await T.expect(b, /\(1\/5/); await T.sleep(250)
      T.send(b, '/login bad2'); await T.expect(b, /\(2\/5/)
      await T.quit(b)
      b = await joinOk('Lock2', '127.0.0.22')
      T.send(b, '/login bad3'); await T.expect(b, /\(3\/5/); await T.sleep(250)
      T.send(b, '/login bad4'); await T.expect(b, /\(4\/5/); await T.sleep(250)
      T.send(b, '/login bad5'); await T.expectKick(b, L.errMaxAttempts)
      b = await joinOk('Lock2', '127.0.0.22')
      T.send(b, `/login ${PW}`); await T.expectKick(b, L.errMaxAttempts)
      await T.sleep(3500)
      b = await joinOk('Lock2', '127.0.0.22')
      T.send(b, `/login ${PW}`); await T.expect(b, L.msgAuthenticated)
    } finally {
      await T.setConfig({ loginCooldownMs: 1000, loginLockoutSeconds: 300 })
    }
  })

  // ========== Sesiones ==========
  await test('grace session: restored from same IP, not from another, other IP does not delete it; position restored', async () => {
    let b = await register('Grace', '127.0.0.30')
    await T.rcon('tp Grace 25 -60 25')
    await T.sleep(500)
    await T.quit(b)
    b = await joinOk('Grace', '127.0.0.30')
    await T.expect(b, L.msgSessionRestored)
    T.assert(T.near(await T.posOf('Grace'), [25.5, -60, 25.5]), `restored at ${await T.posOf('Grace')}`)
    await T.quit(b)
    b = await joinOk('Grace', '127.0.0.31')
    await T.expect(b, L.msgLoginRequest)
    await T.sleep(500)
    T.assert(T.near(await T.posOf('Grace'), SPAWN), 'impostor not at spawn')
    await T.quit(b)
    b = await joinOk('Grace', '127.0.0.30')
    await T.expect(b, L.msgSessionRestored)
    await T.sleep(500)
    T.assert(T.near(await T.posOf('Grace'), [25.5, -60, 25.5]), `owner position not restored after impostor: ${await T.posOf('Grace')}`)
  })

  await test('changepassword: confirm flow, kicks other case variants, keeps online mode columns', async () => {
    const v = await register('Vic', '127.0.0.32')
    const v2 = await joinOk('VIC', '127.0.0.33')
    T.send(v2, `/login ${PW}`)
    await T.expect(v2, L.msgAuthenticated)
    T.send(v, '/changepassword wrongpw newpass2')
    await T.expect(v, L.errOldPasswordWrong)
    T.send(v, `/changepassword ${PW} newpass2`)
    await T.expect(v, L.msgConfirmRequest)
    const before = T.user('Vic').passwordHash
    T.send(v, '/directauth confirm')
    await T.expect(v, L.msgPasswordChanged)
    await T.expectKick(v2, L.msgPasswordChangedElsewhere)
    await T.expectConnected(v, 500)
    const u = T.user('Vic')
    T.assert(u.passwordHash !== before, 'hash not changed')
    await T.quit(v)
    // La sesión de gracia de Vic (misma IP) sigue valiendo para él
    const v3 = await login('Vic', '127.0.0.32', 'newpass2')
    await T.quit(v3)
    const v4 = await joinOk('vic', '127.0.0.34')
    T.send(v4, `/login ${PW}`)
    await T.expect(v4, L.errWrongPassword)
  })

  await test('admin resetpass: invalidates grace sessions and kicks all variants', async () => {
    let r = await register('Rst', '127.0.0.35')
    await T.quit(r) // deja sesión de gracia
    await adminCmd('/directauth resetpass Rst pw22', T.re(L.msgAdminResetSuccess))
    r = await joinOk('Rst', '127.0.0.35')
    await T.expect(r, L.msgLoginRequest)
    T.send(r, '/login pw22')
    await T.expect(r, L.msgAuthenticated)
    const r2 = await joinOk('RST', '127.0.0.36')
    await adminCmd('/directauth resetpass Rst pw33', T.re(L.msgAdminResetSuccess))
    await T.expectKick(r, L.msgAdminPasswordResetKick)
    await T.expectKick(r2, L.msgAdminPasswordResetKick)
    await adminCmd('/directauth resetpass NoSuchUser pw33', T.re(L.errAdminUserNotFound))
  })

  await test('admin unregister and self unregister', async () => {
    const d = await register('Del', '127.0.0.37')
    await adminCmd('/directauth unregister Del', T.re(L.msgAdminUnregisterSuccess))
    await T.expectKick(d, L.msgAccountDeleted)
    T.assert(!T.user('Del'), 'row still present')
    const d2 = await joinOk('Del', '127.0.0.37')
    await T.expect(d2, L.msgWelcome)
    await T.quit(d2)

    const u = await register('Unreg', '127.0.0.38')
    T.send(u, '/unregister wrongpw')
    await T.expect(u, L.errWrongPasswordSimple)
    T.send(u, `/unregister ${PW}`)
    await T.expect(u, L.msgConfirmRequest)
    T.send(u, '/directauth confirm')
    await T.expectKick(u, L.msgAccountDeleted)
    T.assert(!T.user('Unreg'), 'row still present')
  })

  // ========== Hashes antiguos ==========
  await test('legacy hashes log in and end up in the format 1.1/1.2 can read', async () => {
    const make = (iters) => {
      const salt = crypto.randomBytes(16)
      const hash = crypto.pbkdf2Sync('legacy1', salt, iters, 32, 'sha256')
      return salt.toString('base64') + ':' + hash.toString('base64')
    }
    // [nombre, hash guardado, ¿debe reescribirse?]
    const cases = [
      ['OldTen', make(10000), true], // DirectAuth 1.0.0
      ['OldHund', make(100000), false], // 1.1/1.2: ya es el formato actual
      ['OldDev', '100000:' + make(100000), true] // compilaciones previas a la publicación
    ]
    for (const [name, stored, rewritten] of cases) {
      T.db('INSERT INTO users(username, passwordHash, isPremium) VALUES(?, ?, 0)', [name.toLowerCase(), stored], { write: true })
      const b = await joinOk(name, '127.0.0.39')
      await T.expect(b, L.msgLoginRequest)
      T.send(b, '/login wrongpw')
      await T.expect(b, L.errWrongPassword)
      await T.sleep(1100)
      T.send(b, '/login legacy1')
      await T.expect(b, L.msgAuthenticated)
      await T.sleep(800)
      const h = T.user(name).passwordHash
      T.assert(T.verifiesLikeOldVersions(h, 'legacy1'), `${name}: hash not readable by 1.1/1.2: ${h}`)
      T.assert((h !== stored) === rewritten, `${name}: ${rewritten ? 'not rewritten' : 'rewritten for no reason'}`)
      await T.quit(b)
    }
  })

  await test('database: a fresh database gets the current schema and no backup', async () => {
    T.assert(T.schemaVersion() === 1, `user_version ${T.schemaVersion()}`)
    T.assert(T.dbBackups().length === 0, `unexpected backups: ${T.dbBackups()}`)
  })

  // ========== Premium ==========
  const ON = '069a79f4-44e9-4726-a5be-fca90e38aaf5' // Notch
  const OFF = T.offlineUUID('Notch')
  const nd = (u) => u.replace(/-/g, '')

  await test('premium: /online migrates files (RENAME, DIRECTORY, TEXT_REPLACE) and kicks', async () => {
    fs.mkdirSync(path.join(T.WORLD, 'ftbquests'), { recursive: true })
    fs.mkdirSync(path.join(T.WORLD, 'deaths', OFF), { recursive: true })
    fs.writeFileSync(path.join(T.WORLD, 'deaths', OFF, 'death1.txt'), 'x')
    const quest = Buffer.concat([Buffer.from(`owner: "${OFF}"\nid: ${nd(OFF)}\nname: "ñandú"\n`, 'utf8'), Buffer.from([0xff, 0xfe, 0x00, 0x41])])
    fs.writeFileSync(path.join(T.WORLD, 'ftbquests', `${OFF}.snbt`), quest)
    fs.writeFileSync(path.join(T.WORLD, 'ftbquests', `${ON}.snbt`), 'stale')

    const b = await register('Notch', '127.0.0.40')
    await T.rcon('give Notch minecraft:diamond 7')
    await T.sleep(300)
    T.send(b, '/online')
    await T.expect(b, L.msgPremiumWarning)
    T.send(b, '/online wrongpw')
    await T.expect(b, L.errWrongPasswordSimple)
    T.send(b, `/online ${PW}`)
    await T.expect(b, L.msgVerifying)
    await T.expectKick(b, L.msgPremiumKick, 15000)
    await T.sleep(500)
    const u = T.user('Notch')
    T.assert(u.isPremium === 1 && u.onlineUUID === ON && u.offlineUUID === OFF, `db: ${JSON.stringify(u)}`)
    T.assert(exists(`playerdata/${ON}.dat`), 'playerdata not migrated')
    T.assert(exists(`stats/${ON}.json`), 'stats not migrated')
    T.assert(exists(`deaths/${ON}/death1.txt`) && !exists(`deaths/${OFF}`), 'deaths dir not migrated')
    const migrated = fs.readFileSync(path.join(T.WORLD, 'ftbquests', `${ON}.snbt`))
    const expected = Buffer.from(quest.toString('latin1').split(OFF).join(ON).split(nd(OFF)).join(nd(ON)), 'latin1')
    T.assert(migrated.equals(expected), 'TEXT_REPLACE output not byte-exact')
    T.assert(fs.readFileSync(path.join(T.WORLD, 'ftbquests', `${ON}.snbt.bak`), 'utf8') === 'stale', 'existing destination not backed up')
  })

  await test('premium: offline impostors (any case) cannot join a premium account', async () => {
    for (const name of ['Notch', 'notch']) {
      const b = await T.join(name, '127.0.0.41')
      await T.sleep(500)
      T.assert(!b.spawned, `${name} spawned into a premium account`)
    }
  })

  await test('premium: admin disables online mode while offline, data comes back', async () => {
    await adminCmd('/directauth online Notch false', T.re(L.msgAdminPremiumUpdated), 10000)
    const u = T.user('Notch')
    T.assert(u.isPremium === 0 && u.onlineUUID === null && u.offlineUUID === null, `db: ${JSON.stringify(u)}`)
    T.assert(exists(`playerdata/${OFF}.dat`) && !exists(`playerdata/${ON}.dat`), 'playerdata not moved back')
    T.assert(exists(`deaths/${OFF}/death1.txt`), 'deaths not moved back')
    T.assert(fs.readFileSync(path.join(T.WORLD, 'ftbquests', `${OFF}.snbt`), 'latin1').includes(nd(OFF)), 'quest UUIDs not reverted')
    const b = await login('Notch', '127.0.0.42')
    await T.sleep(300)
    T.assert(await T.countItem('Notch', 'minecraft:diamond') === 7, `inventory lost: ${await T.countItem('Notch', 'minecraft:diamond')}`)
  })

  await test('premium: admin enable requires the player online; works when online', async () => {
    await adminCmd('/directauth online Notch true', T.re(L.errAdminPlayerMustBeOnline), 10000)
    T.assert(T.user('Notch').isPremium === 0, 'enabled while offline')
    const b = await login('Notch', '127.0.0.42')
    await adminCmd('/directauth online Notch true', T.re(L.msgAdminPremiumUpdated), 15000)
    await T.expectKick(b, L.msgAdminModeChangedKick)
    const u = T.user('Notch')
    T.assert(u.isPremium === 1 && u.onlineUUID === ON && u.offlineUUID === OFF, `db: ${JSON.stringify(u)}`)
    T.assert(exists(`playerdata/${ON}.dat`), 'playerdata not migrated')
    await adminCmd('/directauth online Notch false', T.re(L.msgAdminPremiumUpdated), 10000)
    T.assert(T.user('Notch').isPremium === 0, 'not disabled again')
  })

  await test('premium: failed migration is rolled back, player stays connected and offline', async () => {
    const b = await login('Notch', '127.0.0.42')
    fs.chmodSync(path.join(T.WORLD, 'stats'), 0o555)
    try {
      T.send(b, `/online ${PW}`)
      await T.expect(b, L.errPremiumMigrationFailed, 15000)
      await T.expectConnected(b, 500)
      T.assert(T.user('Notch').isPremium === 0, 'db changed')
      T.assert(exists(`playerdata/${OFF}.dat`) && !exists(`playerdata/${ON}.dat`), 'playerdata not rolled back')
      T.send(b, 'still_here')
      await T.expectConnected(b, 300)
    } finally {
      fs.chmodSync(path.join(T.WORLD, 'stats'), 0o755)
    }
  })

  await test('premium: names that are not valid Mojang names are never looked up', async () => {
    const b = await register('Notch#', '127.0.0.43')
    T.send(b, `/online ${PW}`)
    await T.expect(b, L.errMojangNotFound, 10000)
    T.assert(T.user('Notch#').isPremium === 0, 'Notch# became premium')
  })

  // ========== Config ==========
  await test('config: malformed JSON on reload keeps old settings and file', async () => {
    const cfgPath = path.join(T.CFG_DIR, 'DirectAuth-config.json')
    const good = fs.readFileSync(cfgPath, 'utf8')
    fs.writeFileSync(cfgPath, '{ "language": "es", ')
    try {
      const out = await T.rcon('directauth reload')
      T.assert(T.re(L.errConfigReloadFailed).test(out), `reload output: ${out}`)
      T.assert(fs.readFileSync(cfgPath, 'utf8') === '{ "language": "es", ', 'malformed file was overwritten')
      const b = await joinOk('CfgBot', '127.0.0.44')
      await T.expect(b, L.msgWelcome) // sigue en inglés
    } finally {
      fs.writeFileSync(cfgPath, good)
      await T.rcon('directauth reload')
    }
  })

  await test('config: unknown migration mode is ignored and the file is not rewritten', async () => {
    const cfgPath = path.join(T.CFG_DIR, 'DirectAuth-config.json')
    const good = fs.readFileSync(cfgPath, 'utf8')
    const bad = good.replace('"TEXT_REPLACE"', '"TEXT_REPLAC"')
    fs.writeFileSync(cfgPath, bad)
    try {
      const out = await T.rcon('directauth reload')
      T.assert(T.re(L.msgConfigReloaded).test(out), `reload output: ${out}`)
      T.assert(fs.readFileSync(cfgPath, 'utf8') === bad, 'file was rewritten')
    } finally {
      fs.writeFileSync(cfgPath, good)
      await T.rcon('directauth reload')
    }
  })

  // ========== Extra ==========
  await test('jail: item pickup and mounting are blocked', async () => {
    let b = await register(process.env.PICK || 'Pick', '127.0.0.70')
    await logout(b)
    const PICK = process.env.PICK || 'Pick'
    b = await joinOk(PICK, '127.0.0.70')
    await T.expect(b, L.msgLoginRequest)
    await T.sleep(500)
    const p = await T.posOf(PICK)
    await T.rcon(`summon minecraft:item ${p[0]} ${p[1] + 0.5} ${p[2]} {Item:{id:"minecraft:emerald",Count:3b},PickupDelay:0}`)
    await T.sleep(1500)
    T.assert(await T.countItem(PICK, 'minecraft:emerald') === 0, 'picked up an item while jailed')
    await T.rcon('kill @e[type=item]')
    await T.rcon(`summon minecraft:boat ${p[0] + 1} ${p[1]} ${p[2]}`)
    await T.sleep(500)
    const boat = Object.values(b.entities).find((e) => e.name === 'boat')
    T.assert(boat, 'boat not visible')
    b.mount(boat)
    await T.sleep(800)
    T.assert(!(await rconTest(`execute as ${PICK} if entity @s[nbt={RootVehicle:{}}]`)), 'mounted the boat while jailed')
    // Control positivo: tras el login sí se puede
    T.send(b, `/login ${PW}`)
    await T.expect(b, L.msgAuthenticated)
    await T.sleep(500)
    await T.rcon('kill @e[type=boat]')
    const pl = await T.posOf(PICK)
    await T.rcon(`summon minecraft:boat ${pl[0] + 1} ${pl[1]} ${pl[2]}`)
    await T.sleep(500)
    const boat2 = Object.values(b.entities).find((e) => e.name === 'boat')
    b.mount(boat2)
    await T.sleep(800)
    T.assert(await rconTest(`execute as ${PICK} if entity @s[nbt={RootVehicle:{}}]`), 'control: could not mount after login')
    b.dismount()
    await T.sleep(300)
    await T.rcon('kill @e[type=boat]')
    const p2 = await T.posOf(PICK)
    await T.rcon(`summon minecraft:item ${p2[0]} ${p2[1] + 0.5} ${p2[2]} {Item:{id:"minecraft:emerald",Count:3b},PickupDelay:0}`)
    await T.sleep(1500)
    T.assert(await T.countItem(PICK, 'minecraft:emerald') === 3, 'control: could not pick up after login')
  })

  await test('changepassword keeps Online Mode columns', async () => {
    const b = await register('KeepOn', '127.0.0.71')
    T.db("UPDATE users SET isPremium = 1, onlineUUID = '00000000-0000-0000-0000-000000000001', offlineUUID = 'x' WHERE username = 'keepon'", [], { write: true })
    T.send(b, `/changepassword ${PW} newpass9`)
    await T.expect(b, L.msgConfirmRequest)
    T.send(b, '/directauth confirm')
    await T.expect(b, L.msgPasswordChanged)
    const u = T.user('KeepOn')
    T.assert(u.isPremium === 1 && u.onlineUUID === '00000000-0000-0000-0000-000000000001' && u.offlineUUID === 'x', `columns changed: ${JSON.stringify(u)}`)
  })

  await test('duplicate login by an impostor does not move the owner', async () => {
    const owner = await register('Own', '127.0.0.72')
    await T.rcon('tp Own 33 -60 33')
    await T.sleep(500)
    const imp = await joinOk('Own', '127.0.0.73')
    await T.expectKick(owner, /.*/) // vanilla: "You logged in from another location"
    await T.expect(imp, L.msgLoginRequest)
    await T.sleep(500)
    T.assert(T.near(await T.posOf('Own'), SPAWN), `impostor not at spawn: ${await T.posOf('Own')}`)
    await T.quit(imp)
    const back = await joinOk('Own', '127.0.0.72')
    await T.expect(back, L.msgSessionRestored)
    await T.sleep(500)
    T.assert(T.near(await T.posOf('Own'), [33.5, -60, 33.5]), `owner not restored: ${await T.posOf('Own')}`)
  })

  await test('positions file: never-played players are not stored, jailed players are', async () => {
    const POSCHK = 'PosChk' + crypto.randomBytes(2).toString('hex')
    const jailed = await register(POSCHK, '127.0.0.' + (80 + Math.floor(Math.random() * 100)))
    await logout(jailed)
    const j2 = await joinOk(POSCHK, jailed.ip)
    await T.expect(j2, L.msgLoginRequest)
    const NEVER = 'Never' + crypto.randomBytes(3).toString('hex')
    const fresh = await joinOk(NEVER, '127.0.0.74')
    await T.expect(fresh, L.msgWelcome)
    await T.sleep(800)
    const pos = JSON.parse(fs.readFileSync(path.join(T.CFG_DIR, 'DirectAuth_positions.json'), 'utf8'))
    T.assert(!pos[T.offlineUUID(NEVER)], 'never-played player stored')
    T.assert(pos[T.offlineUUID(POSCHK)], 'jailed player not stored')
  })

  await test('admin commands over RCON return their result', async () => {
    const timed = async (cmd) => { const t0 = Date.now(); const out = await T.rcon(cmd); return { out, ms: Date.now() - t0 } }
    let r = await timed('directauth resetpass NoSuchUser2 abcd')
    T.assert(T.re(L.errAdminUserNotFound).test(r.out), `not found: ${JSON.stringify(r.out)}`)
    const b = await register('RconUser', '127.0.0.100')
    r = await timed('directauth resetpass RconUser newpass5')
    T.assert(T.re(L.msgAdminResetSuccess).test(r.out), `resetpass: ${JSON.stringify(r.out)}`)
    await T.expectKick(b, L.msgAdminPasswordResetKick)
    console.log(`      resetpass over RCON answered in ${r.ms}ms`)
    r = await timed('directauth online RconUser true')
    T.assert(T.re(L.errAdminPlayerMustBeOnline).test(r.out), `online offline player: ${JSON.stringify(r.out)}`)
    r = await timed('directauth unregister RconUser')
    T.assert(T.re(L.msgAdminUnregisterSuccess).test(r.out), `unregister: ${JSON.stringify(r.out)}`)
    T.assert(!T.user('RconUser'), 'row still present')
    // Con consulta a Mojang y migración de datos
    const n = await login('Notch', '127.0.0.42')
    r = await timed('directauth online Notch true')
    T.assert(/Online Mode status updated for Notch: true/.test(r.out), `online true: ${JSON.stringify(r.out)}`)
    await T.expectKick(n, L.msgAdminModeChangedKick)
    console.log(`      online true (Mojang + migration) over RCON answered in ${r.ms}ms`)
    r = await timed('directauth online Notch false')
    T.assert(/Online Mode status updated for Notch: false/.test(r.out), `online false: ${JSON.stringify(r.out)}`)
    // Los comandos que no son de DirectAuth no esperan
    r = await timed('list')
    T.assert(/players online/.test(r.out) && r.ms < 500, `list: ${JSON.stringify(r)}`)
    r = await timed('directauth reload')
    T.assert(T.re(L.msgConfigReloaded).test(r.out), `reload: ${JSON.stringify(r.out)}`)
  })

  await test('disconnect during /register: account is created, nothing leaks to the next connection', async () => {
    const NAME = 'Quick' + crypto.randomBytes(2).toString('hex')
    const b = await joinOk(NAME, '127.0.0.94')
    await T.sleep(1100)
    b.chat(`/register ${PW}`)
    b._client.socket.destroy() // cortar sin esperar respuesta
    await T.sleep(1000)
    T.assert(T.user(NAME), 'account not created')
    // Según quién gane la carrera, la primera conexión llegó o no a autenticarse (y desde su IP
    // habría sesión de gracia); desde otra IP siempre hay que hacer login
    const b2 = await joinOk(NAME, '127.0.0.101')
    await T.expect(b2, L.msgLoginRequest)
    T.send(b2, 'chat_should_be_blocked')
    await T.expect(b2, L.msgUseCommands)
  })

  await test('disconnect during /login: the result never authenticates a newer connection', async () => {
    const NAME = 'Race' + crypto.randomBytes(2).toString('hex')
    let b = await register(NAME, '127.0.0.95')
    await logout(b)
    for (let i = 0; i < 3; i++) {
      b = await joinOk(NAME, '127.0.0.95')
      await T.expect(b, L.msgLoginRequest)
      await T.sleep(1100)
      b.chat(`/login ${PW}`)
      b._client.socket.destroy()
      // Reentrar desde otra IP (sin sesión de gracia) mientras el hash está en curso
      const imp = await joinOk(NAME, '127.0.0.96')
      await T.sleep(800)
      T.send(imp, 'should_be_blocked')
      await T.expect(imp, L.msgUseCommands)
      await T.quit(imp)
    }
  })

  await T.closeRcon()
  process.exit(T.summary() ? 1 : 0)
})().catch((e) => { console.error(e); process.exit(2) })
