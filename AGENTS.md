# AGENTS.md — DirectAuth

Single source of truth for any AI coding agent working in this repo (Claude Code, Gemini, Codex, Cursor, Aider, etc.). Keep this file up to date when architecture changes; do not maintain parallel `CLAUDE.md` / `GEMINI.md` copies.

---

## 1. Project Overview

**DirectAuth** is a server-side Minecraft authentication mod for **Fabric 1.21.1** (Fabric API, Java 21). This is the `fabric-1.21.1` branch, a port of the NeoForge 1.21.1 version on `main`; keep behavior identical to `main` and port fixes between the branches. It lets a server run in offline mode safely by forcing players to `/register` and `/login` before they can play, with optional opt-in conversion to premium (online-mode) accounts that log in automatically through Mojang. An account keeps the UUID it plays with when it changes mode, so no player data ever moves.

- **Mod ID:** `directauth`
- **Main class:** `com.marcp.directauth.DirectAuth`
- **License:** MIT
- **Side:** server-only. `"environment": "server"` in `fabric.mod.json`, so Fabric Loader does not load it on clients (no singleplayer/LAN). No network channels of its own: players join with any client the rest of the server accepts.

The `Documentation-NeoForge/` and `Documentation/` directories in the repo root are **not part of this mod** — they are the upstream NeoForged docs and the upstream Forge docs (used by `main` and `forge-1.20.1`), copied for reference and git-ignored. Do not treat them as production code or modify them as part of mod work. Fabric API sources, decompiled vanilla and NeoForge sources for comparison go in `build/references/` (git-ignored, indexed in its `README.md`).

---

## 2. Tech Stack & Versions

Authoritative values live in `gradle.properties`; the table below is a quick reference.

| Item | Value |
|---|---|
| Minecraft | `1.21.1` (`fabric.mod.json`: exactly `1.21.1`) |
| Fabric Loader | `0.20.1` (`>=0.20.1`) |
| Fabric API | `0.116.17+1.21.1` (required: `>=` the version it is built against) |
| Java | 21 (toolchain pinned) |
| Mappings | Mojang + Parchment `2024.07.28` for MC `1.21` (`loom.layered`): the same names as `main`, so mixins target the same members |
| Gradle plugin | `net.fabricmc.fabric-loom-remap` `1.17.21` (the newest Loom that runs on Java 21; 1.18+ needs a Java 25 JVM) |
| Persistence | SQLite via `org.xerial:sqlite-jdbc:3.46.0.0` (jar-in-jar through Loom `include`) |
| Gradle wrapper | 9.8.2 |

Mixins are enabled (`directauth.mixins.json`, declared in `fabric.mod.json`). Production Fabric runs on intermediary names: `remapJar` (part of `build`) remaps the mixin annotations themselves, so there is no refmap; a mixin target that only works in dev is a real bug — test with the production jar. The mixin config plugin `DirectAuthMixinPlugin` adds the optional compat mixins only when their mod is loaded. One access widener (`directauth.accesswidener`) opens what NeoForge's own ATs/extensions gave `main`: `ServerLoginPacketListenerImpl$State`, `ServerCommonPacketListenerImpl.connection` (NeoForge's `getConnection()`) and `CommandSourceStack.source`.

---

## 3. Repo Layout

```
DirectAuth/
├── build.gradle              # Fabric Loom, layered mappings, include(sqlite-jdbc)
├── gradle.properties         # all versions & mod metadata
├── settings.gradle           # plugin management
├── src/
│   ├── main/
│   │   ├── java/com/marcp/directauth/
│   │   │   ├── DirectAuth.java       # ModInitializer ("main" entrypoint) — registers handlers, exposes singletons
│   │   │   ├── auth/                 # LoginManager, AuthTasks, MojangAPI, MojangVerifiedConnection, ConfirmationManager
│   │   │   ├── commands/             # Brigadier command classes
│   │   │   ├── config/               # ModConfig, LangConfig (Gson JSON)
│   │   │   ├── data/                 # DatabaseManager (SQLite), PositionManager, UserData, StoredLocation
│   │   │   ├── events/               # ConnectionHandler, PlayerRestrictionHandler
│   │   │   ├── network/              # JailTraffic (other mods' packets and menus while jailed), JailPackets (vanilla allowlist)
│   │   │   └── mixin/                # login handshake and account UUID, packet restrictions, respawn, effects, RCON response, the events Fabric API lacks
│   │   ├── resources/
│   │   │   ├── fabric.mod.json       # expanded from gradle.properties by processResources
│   │   │   ├── directauth.accesswidener
│   │   │   ├── directauth.mixins.json
│   │   │   └── assets/directauth/    # (lang/ here is placeholder — real i18n is JSON in worldserverconfig)
│   ├── testmod/              # Fabric test mod `directauth_test` for the smoke test (gradle `testmodJar` → build/testmod/, never in the jar)
│   └── generated/resources/  # output of `runData`
├── Documentation-NeoForge/   # GIT-IGNORED — upstream NeoForged docs, not part of this mod
├── Documentation/            # GIT-IGNORED — upstream Forge docs (forge-1.20.1 branch), not part of this mod
├── tools/smoke-test/         # end-to-end tests: production Fabric server + Mineflayer bots (see its README)
├── tools/mod-tests/          # end-to-end tests with real third-party mods: NeoForge client under Xvfb (see its README; not ported to Fabric yet)
├── run/  runs/  run-data/    # gitignored dev runtime dirs (the smoke test uses runs/smoke-test-fabric/; runs/fabric-server/ is a production Fabric server for manual checks)
├── build/  .gradle/  repo/   # gitignored gradle output
├── .github/workflows/build.yml  # CI: gradlew build on push/PR (Ubuntu, Temurin 21)
├── AGENTS.md                 # ← you are here
└── README.md
```

---

## 4. Source Architecture

### 4.1 Entrypoint — `DirectAuth.java`
- `ModInitializer` (the `main` entrypoint in `fabric.mod.json`); `onInitialize()` creates the first `LoginManager`.
- Calls `ConnectionHandler.register()` and `PlayerRestrictionHandler.register()` (Fabric API callbacks), and registers commands via `CommandRegistrationCallback`. Where Fabric API has no event, a mixin calls the handler at the point where NeoForge fires its event (see §4.6).
- Holds static singletons exposed via getters: `LoginManager`, `DatabaseManager`, `PositionManager`, `ModConfig`. **All cross-package access goes through these getters** — there is no DI container.
- `initDatabase(Path)` and `initConfig(Path)` are called by `ConnectionHandler#onServerStarted`, *not* in `onInitialize` (they need the world path). `initConfig` returns `false` (keeping the previous config, or defaults on first start) if the JSON is unreadable; it never overwrites a malformed file.
- **One `LoginManager` per server instance**: `resetLoginManager()` runs on `SERVER_STARTED`, and `SERVER_STOPPED` shuts it down and closes the DB (`closeDatabase()`). Singleplayer starts several servers in one JVM, so never keep auth state in statics that survive a stop.

### 4.2 `auth/`
- **`LoginManager`** — central authentication state. Thread-safe (`ConcurrentHashMap`). Holds `authenticatedPlayers`, `failedLogins` + `loginAttempts` (**keyed by lowercased account name + IP**, not UUID: they survive reconnects and cover case variants, but a stranger on another IP cannot lock the owner out; failures count within max(15 min, `loginLockoutSeconds`), the cooldown entry expires after `loginCooldownMs`; `clearFailedLogins(name)` lifts a lockout), `connectionTimes` (login timeout), `preLoginCache` (mixin-populated; `NULL_MARKER` = "not registered"; entries expire after 10 min), `graceSessions` (IP-locked re-login, invalidated per account with `invalidateSessions(name)`), `pendingOperations` (one async password operation per player). Runs a scheduler for cleanup at `sessionCleanupInterval` minutes and a 2-thread hash pool.
- **Password hashing is PBKDF2WithHmacSHA256**, 100k iterations, 256-bit key, stored as `salt:hash` (Base64) — **the format 1.1/1.2 read (they assume 100k), so downgrading keeps logins working. Don't change it** (raising iterations needs a self-describing format and breaks downgrades). `verifyPassword` also accepts 10k `salt:hash` hashes from 1.0.0 and `iterations:salt:hash` from pre-release builds, returning `OK_NEEDS_REHASH`; `/login` then rewrites them as 100k `salt:hash`. Never hash on the server thread — use `hashPasswordAsync` / `verifyPasswordAsync`.
- **`AuthTasks`** — `begin(player)` reserves the player's single in-flight operation; `finish(...)` brings a future back to the server thread, re-resolves the player (same connection only) and reports failures (including exceptions thrown by the callback) with `errInternal`. `onServerThread(server)` is the executor to use instead of `server`: once the server is stopping, `server.execute` would run the task inline on the DB/hash/HTTP thread, so it drops it instead. `revokeAccountAccess(...)` invalidates grace sessions and kicks **every** case variant of an account. `finish(CommandSourceStack, ...)` (admin commands) registers operations started from RCON so the RCON response waits for their result (see `MixinRconConsoleSource`).
- **Online Mode keeps the account's UUID.** `/online` and `/directauth online` only write the mode columns (`setOnlineModeAsync`, never the password), and the player stays connected. Enabling stores the UUID the player is playing with as `playerUUID`; `MixinServerLoginPacketListenerImpl` then gives every login of the account that UUID, also the Mojang-verified ones (with the Mojang skin). **Never move player files between UUIDs**: most mods keep per-player data in memory, write it back under the old UUID and treat the new UUID as a new player (Starter Kit replaced a whole inventory with its kit, FTB Quests/Teams started over). Accounts made premium before 1.3.1 had their data migrated to the Mojang UUID, and keep that UUID (`playerUUID` = `onlineUUID` once switched back to offline).
- **`MojangVerifiedConnection`** — implemented by `Connection` (`MixinConnection`): the Mojang UUID the session server confirmed during the login of that connection. `ConnectionHandler` auto-authenticates a premium account only if it equals `onlineUUID` — the player's UUID proves nothing, since the account plays with its own.
- **`MojangAPI`** — non-blocking UUID lookup (`HttpClient.sendAsync`, 5s connect / 10s request timeout) against `api.mojang.com/users/profiles/minecraft/{name}`. Only names matching `[A-Za-z0-9_]{3,16}` are ever sent (offline names may contain `#`, `?`, `/`… which would change the URL), and the returned profile name must match. Completes with `null` if the account does not exist and **exceptionally** if Mojang is unreachable. Callbacks must be marshalled back to the main thread.
- **`ConfirmationManager`** — pending-action store (30s TTL, `Consumer<ServerPlayer>`) used by `/changepassword` and `/unregister`, drained by `/directauth confirm`; cleared on logout and server stop.

### 4.3 `commands/`
All Brigadier-based. Registered through `CommandRegistrationCallback` in `DirectAuth`.

| Command | Args | Auth required? | Notes |
|---|---|---|---|
| `/register <password>` | 1 | no | Enforces `registrationDelay`, `maxAccountsPerIP`, password length bounds. Auto-authenticates on success. |
| `/login <password>` | 1 | no | Enforces `loginCooldownMs`, `maxLoginAttempts` / `loginLockoutSeconds` (per account + IP). Restores position on success; upgrades legacy hashes. |
| `/online [password]` | 0–1 | yes | Switches account to premium; verifies via `MojangAPI`; stores the current UUID as `playerUUID`. No kick, no file moves. |
| `/changepassword <old> <new>` | 2 | yes | Requires `/directauth confirm`. Keeps Online Mode. Revokes the account's other sessions and connections. |
| `/unregister <password>` | 1 | yes | Requires `/directauth confirm`. Kicks player after deletion. |
| `/logout` | 0 | yes | Invalidates grace session and kicks. |
| `/directauth confirm` | 0 | varies | Drains `ConfirmationManager`. |
| `/directauth online <user> <bool>` | 2 | **op** | Admin: toggle Online Mode (Mojang lookup when enabling). Enabling needs the target online **and authenticated** unless the account already has a `playerUUID` (its exact-case offline UUID is unknown otherwise); disabling always works. Nobody is kicked. |
| `/directauth resetpass <user> <newpw>` | 2 | **op** | Admin: force password reset; revokes all sessions/connections of the account and clears its login lockout. |
| `/directauth unregister <user>` | 1 | **op** | Admin: force-delete account; revokes all sessions/connections of the account. |
| `/directauth reload` | 0 | **op** | Re-reads config + language files; fails (keeping the old config) on a malformed `DirectAuth-config.json`. A malformed language file is logged and replaced by that language's built-in defaults. |
| `/directauth resetlang` | 0 | **op** | Deletes the en/es language files so they regenerate from defaults. |

### 4.4 `events/`
- **`ConnectionHandler`** — `ServerLifecycleEvents.SERVER_STARTED` (fresh `LoginManager`, init DB/config/scheduler; `DatabaseManager` creates `world/serverconfig/`, which NeoForge creates by itself), `SERVER_STOPPED` (close DB, shut down `LoginManager`, clear anchors/confirmations; runs *after* players are disconnected), `onPlayerLogin` — called by `MixinPlayerList` at the end of `PlayerList.placeNewPlayer`, where NeoForge fires `PlayerLoggedInEvent`; Fabric API's `ServerPlayConnectionEvents.JOIN` fires earlier, before the player is in the level and the player list — (consume mixin pre-cache, attempt grace-session restore only if the account exists; otherwise a player who has played before is teleported to spawn with the original position saved, while a never-played player stays where the game or a first-join mod put them; the anchor is reset either way), `onPlayerLogout` — `MixinPlayerList`, head of `PlayerList.remove`, like `PlayerLoggedOutEvent` — (pause session if authenticated, else drop; discards what `JailTraffic` held for that connection).
- **`PlayerRestrictionHandler`** — enforces the unauthenticated-player jail. `isRestricted(entity)` is the single check (real `ServerPlayer`, **not a `FakePlayer`**, not authenticated) — mixins use it too. Anchors players with a `Map<UUID, Anchor(dimension, Vec3)>` from `ServerTickEvents.END_SERVER_TICK`, iterating the connected players (teleports back across dimensions if needed). A jailed player the level has removed (dead, not yet respawned) is not moved and only gets an anchor if it has none, as `EntityTickEvent.Post` on `main` still fires in the tick of the removal: a player who rejoins dead is removed in their first tick, and is anchored at the world spawn `ConnectionHandler` sent them to, so respawning while jailed lands there. The `loginTimeout` kick and the reminder also apply to them, counted with the server's tick (their `tickCount` stops): otherwise anyone could sit on an account's death screen forever (`main` and `forge-1.20.1` still have that bug: `EntityTickEvent` / the `!isRemoved()` filter skip dead players). **Not** a player tick: `ServerPlayer.doTick()` runs inside the connection tick, which then resets the player to its start-of-tick position and would undo the anchor teleport. Fabric API callbacks cancel block break (`PlayerBlockBreakEvents.BEFORE`), left click (`AttackBlockCallback`, which also resends the block), right click on blocks and with items (`UseBlockCallback`, `UseItemCallback`, `FAIL`), entity interact (`UseEntityCallback`, both INTERACT and INTERACT_AT for armor stands) and all chat (`ServerMessageEvents.ALLOW_CHAT_MESSAGE`; a fallback: `JailPackets.dropChat` already drops it). Mixins handle the rest (see §4.6): commands (`MixinCommands` → `onCommand`, only `register`/`login`/`online`), damage (`onDamage`), and attack, item and XP pickup, mount and healing (gated directly on `isRestricted`). A jailed player who rejoins on a vehicle is put back on it by `placeNewPlayer` (see `MixinEntityStartRiding`), then dismounted by the teleport to spawn or, failing that, by the jail tick; the vehicle stays where it was. The tick only copies the jailed players (kicking one changes the player list). Item drops and inventory/container clicks are blocked at the packet level by `MixinServerGamePacketListenerImpl` — **don't** handle `ItemTossEvent`: the game itself fires it for drops (closing menus on disconnect, `/give`). `release(player)` removes the anchor, resyncs effects and calls `JailTraffic.release`; it runs after `/login` and `/register`, and from `ConnectionHandler` for auto-login and restored sessions. Sends the auth reminder every ~5 seconds (100 ticks). Kicks if `loginTimeout` elapsed.

### 4.5 `network/` — the jail and other mods
Many mods act at first join or accept client packets without knowing about authentication. Everything here keys off `PlayerRestrictionHandler.isRestricted`.
- **`JailTraffic`** — per jailed connection, until it authenticates:
  - **Payloads the client sends to mods** are held in `MixinConnection` (`genericsFtw`, network thread: before Fabric API's dispatch at the head of `handleCustomPayload` and before any mod that hooks it directly; only the last of each payload type, in arrival order) and processed through `ServerGamePacketListenerImpl.handleCustomPayload` (so Fabric API dispatches them; the player is authenticated by then, so they are not held again) on `release`. Payloads of the `minecraft`, `c`, `fabric` and `fabric-*` namespaces (channel registration, brand, Fabric API's own) are never held or deferred, nor is a `DiscardedPayload` (an id no mod registered; nothing handles it, and holding it would let a client invent unlimited types). A connection holds at most 64 types and 1 MiB (size measured by re-encoding the payload); what does not fit is dropped (logged once). `release` sends the deferred screens, runs the held payloads and opens the deferred menu in that order, as on `main`: Fabric API calls play receivers through `MinecraftServer.execute`, which runs them inline on the server thread but queues them inside a task of the server's queue (where the result of `/login` and `/register` arrives, see `AuthTasks`), so the menu is opened through the same queue (`AuthTasks.onServerThread`), after them. Held payloads are dropped if the connection leaves first. This stops an impostor from acting for the account (Cobblemon releases, Vampirism bites, choosing an origin) while one-shot handshakes (Simple Voice Chat's secret request) still work after `/login`. Holding instead of dropping, and keeping only the last per type, are deliberate: Epic Fight sends a packet every tick. `heldPayloadExceptions` lists payloads that may pass (a mod's timed "I have the mod" reply).
  - **Screen-opening payloads** the server sends (`deferredScreenPayloads`, incl. inside `ClientboundBundlePacket`s) are delivered on `release`, last of each id (Pixelmon resends every 5 s).
  - **Menus the server opens** (`ServerPlayer.openMenu`; a jailed player cannot open one themselves) are opened on `release` (Tensura's race menu). The caller gets `OptionalInt.empty()`, so anything it would send keyed by the container id is not replayed (merchant offers do this, but trading needs an interaction the jail blocks).
  - A deferred screen is delivered even if the client already acted on the mod while jailed, because DirectAuth cannot tell it became stale (Pixelmon's client can show its starter screen in the jail from a previous session; after `/login` the screen reopens once and one more pick closes it).
  - Netty threads add, the server thread releases: each connection's queue has its own lock, and adding re-checks under it that the connection is still open, jailed and not yet released (otherwise the packet takes its normal path). A payload that arrives after authentication but before `release` drops the held one of the same type, which is older.
- **`JailPackets`** — allowlist of vanilla serverbound packets a jailed player may send; the rest are dropped in `MixinConnection`. Chat messages (not commands) are dropped there too (`dropChat`), before any mod reads them (MCA's villager AI hooks the start of `handleChat` and sends the text to an external service); they still go through vanilla `handleChat`'s validation without being broadcast (last-seen acknowledgements, illegal characters, and the signed-message chain, which would otherwise fall behind the client's and reject their next message after `/login`) and counted by vanilla's chat spam kick, and the player gets `msgUseCommands` (unless `/login` finished in between). Some check permissions themselves (command blocks, difficulty, structure blocks, NBT queries) and an op impostor has those permissions. Interaction packets stay allowed because the jail's events cancel them *and* correct the client.

### 4.6 `mixin/`
- **`MixinServerLoginPacketListenerImpl`** — vanilla, same as `main`. Fabric API's login networking (`ServerLoginNetworkHandlerMixin`) only delays `verifyLoginAndFinishConnectionSetup` in `tick` until its login queries finish, after DirectAuth has swapped the profile in `startClientVerification`; it does not touch `handleHello`/`handleKey`. Async pre-load of `UserData` during the login handshake so neither the handshake nor `PlayerLoggedInEvent` blocks on SQLite. Phase 2 only *peeks* the cache. Exceptions from re-running vanilla `handleHello` inside the future disconnect the client instead of being swallowed. `startClientVerification` (both paths: offline on the server thread, premium on the authenticator thread) records a Mojang-confirmed UUID on the connection (`MojangVerifiedConnection`; not the singleplayer offline fallback) and swaps the profile's UUID for the account's UUID (`UserData#getAccountUUID`, keeping the skin properties): for premium accounts only when Mojang confirmed `onlineUUID`, for offline accounts on the offline path (every case variant of the name then shares that UUID, like the exact name already did). Not on online-mode servers. `ConnectionHandler` disconnects a join that did not get the account's UUID (e.g. the cache entry was dropped mid-login), since mods would treat it as a new player.
- **`MixinLivingEntityEffects`** — cancels `tickEffects` for restricted players so effect durations freeze while in the jail (the client HUD is hidden/resynced by `PlayerRestrictionHandler`).
- **`MixinServerPlayerRespawn`** — makes `findRespawnPositionAndUseSpawnBlock` return world spawn for restricted players, so respawning neither reveals the account's bed/anchor nor consumes an anchor charge. After `/login`, `LoginCommand` calls the same vanilla method (now unrestricted) so anchor charges and invalid spawn points follow vanilla rules.
- **`MixinServerGamePacketListenerImpl`** — cancels drop actions, offhand swaps (F), pick-item (middle click), container clicks and creative-slot packets for restricted players (injected *after* `PacketUtils.ensureRunningOnSameThread`, i.e. on the server thread) and resyncs the client inventory.
- **`MixinItemPhysicPackets`** — `@Pseudo` compat mixin (`remap = false`, `require = 0`), added by `DirectAuthMixinPlugin` only when the mod id `itemphysic` is loaded (Fabric logs a WARN for every missing `@Pseudo` target otherwise); the method is named without a descriptor because `remap = false` would leave `ServerPlayer` unmapped in production. It targets ItemPhysic's `DropPacket` / `PickupPacket#executeServer`: with `throw.enabled` the client sends Q as a CreativeCore packet instead of `ServerboundPlayerActionPacket`, which drops the item without passing through `handlePlayerAction`. Cancels both for restricted players (resyncs the inventory, `msgNoDrop` on drop). CreativeCore runs them on the server thread (`enqueueWork`).
- **`MixinRconConsoleSource`** — vanilla reads the RCON response as soon as the command returns, before async admin commands finish. Before `getCommandResponse()` the RCON thread (never the server thread) waits, up to 30 s, for the operations registered by `AuthTasks.finish`; `prepareForCommand()` forgets operations of previous commands. Admin commands must go through `AuthTasks.finish(CommandSourceStack, ...)` for RCON to see their output.
- **`MixinServerCommonPacketListenerImpl`** — head of `send(Packet, PacketSendListener)`: defers screen payloads via `JailTraffic` (`ServerPlayNetworking.send` goes through it).
- **`MixinConnection`** — `genericsFtw` (every received packet, network thread): holds mod payloads via `JailTraffic.holdFromClient` and drops vanilla packets of jailed players that `JailPackets` does not allow. Also implements `MojangVerifiedConnection`.
- **`ServerGamePacketListenerAccessor`** — invokers for the private steps of vanilla `handleChat`, used by `JailPackets.dropChat`.
- **`MixinServerPlayerOpenMenu`** — defers vanilla `openMenu(MenuProvider)` for jailed players via `JailTraffic` (Fabric API's `ExtendedScreenHandlerFactory` menus go through it too).
- **Events Fabric API lacks**, each a thin mixin that calls `PlayerRestrictionHandler`/`ConnectionHandler` where NeoForge fires the event: **`MixinPlayerList`** (`placeNewPlayer` TAIL / `remove` HEAD: logged in / out; it also marks the player being placed, from `placeNewPlayer` HEAD to TAIL), **`MixinCommands`** (`Commands.performCommand` HEAD, like `CommandEvent`: player commands, signed or not; console/RCON have no entity and pass), **`MixinPlayerDamage`** (`Player.actuallyHurt`: the damage after armor and effects is set to 0 before absorption is spent, like `LivingDamageEvent.Pre`; the hit itself still lands — knockback, armor wear — as on `main`; `ServerLivingEntityEvents.ALLOW_DAMAGE` would cancel the whole hit), **`MixinPlayerAttack`** (`Player.attack` HEAD, like `AttackEntityEvent`: every caller, not only the interact packet as Fabric's `AttackEntityCallback`; a jailed spectator can still switch camera, as on `main`), **`MixinLivingEntityHeal`** (`heal`), **`MixinItemEntityPickup`** / **`MixinExperienceOrbPickup`** (`playerTouch`), **`MixinEntityStartRiding`** (`startRiding(Entity, boolean)`, also forced mounts like NeoForge's `EntityMountEvent`, **except** while `placeNewPlayer` puts the player back on the vehicle they logged out on: vanilla would otherwise discard the vehicle and its passengers — `main` and `forge-1.20.1` still have that bug).

### 4.7 `data/`
- **`DatabaseManager`** — single-threaded `ExecutorService` wrapping SQLite; **every public method is async** and the sync helpers are private, so check-then-write inside one task is atomic (`registerUserAsync` re-checks existence and `maxAccountsPerIP` next to the INSERT). `close()` waits for queued writes. The schema version lives in `PRAGMA user_version` (`SCHEMA_VERSION`; 0 = 1.2.0 or older): when an existing database is older, `initDatabase` first saves a consistent copy with `VACUUM INTO` as `serverconfig/directauth-backup-<timestamp>.db`, so server owners never need a manual backup before upgrading. Table:
  ```sql
  users(username TEXT PRIMARY KEY, passwordHash TEXT NOT NULL,
        isPremium INTEGER DEFAULT 0, onlineUUID TEXT, registrationIp TEXT,
        offlineUUID TEXT, playerUUID TEXT)
  ```
  Keys are lowercased with `Locale.ROOT`; lookups use `username IN (?, ?)` to also match rows written by older versions with the system locale (e.g. Turkish dotless `ı`).
  All CRUD is `*Async` returning `CompletableFuture` (SQL errors complete the future exceptionally). On startup, if `DirectAuth_users.json` exists, it is imported in one transaction and renamed to `DirectAuth_users.json.MIGRATED` **only after a successful commit**.
- **`UserData`** — POJO mirror of the table row. `username` is always lowercased. `playerUUID` is the UUID the account plays with (null = whatever the login gives: the offline UUID of the name typed, or the Mojang UUID for accounts made premium before 1.3.1); `getAccountUUID()` resolves that rule, use it instead of reading `playerUUID`. `offlineUUID` is only written by 1.2.x–1.3.0 (they migrated data back to it) and is cleared on every mode change.
- **`PositionManager`** — JSON store of `UUID → StoredLocation` (dimension, x/y/z, yaw/pitch, health, food, saturation, fire ticks). Populated on join, drained on successful login. Written atomically (temp file + move) on its own thread from a snapshot, flushed on stop; a corrupt file is moved aside to `*.corrupt-<ts>` instead of crashing startup. Players who have never played (zero play time) are not stored — they are at spawn anyway.

---

## 5. Configuration & Localization

### 5.1 `ModConfig` (`world/serverconfig/DirectAuth-config.json`)
Gson, pretty-printed. Loaded once on `SERVER_STARTED`; missing fields are filled with defaults and the file rewritten.

| Group | Fields (defaults) |
|---|---|
| General | `language` (`"en"`) |
| Session | `sessionGracePeriod` (600s), `sessionCleanupInterval` (10 min) |
| Password | `minPasswordLength` (4), `maxPasswordLength` (32) |
| Login flood | `maxLoginAttempts` (5), `loginCooldownMs` (3000), `loginLockoutSeconds` (300), `loginTimeout` (60s) |
| Anti-bot | `registrationDelay` (1s), `maxAccountsPerIP` (5) |
| Other mods | `deferredScreenPayloads` (Neo Origins, MCA, Pixelmon, Dragon Survival and Starter Kit screen payloads), `heldPayloadExceptions` (Starter Kit's timed reply), `logJailPayloads` (false; logs the payload ids jailed players receive, to find a new mod's screen), `otherModsDefaultsVersion`. Built-in ids live in `ModConfig.DEFAULT_SCREEN_PAYLOADS` / `DEFAULT_HELD_EXCEPTIONS` with the version that added them; bump `OTHER_MODS_DEFAULTS` when adding one so existing configs get it on load (ids an admin removed later don't come back). | **On this branch the built-in ids are still `main`'s NeoForge mods** (Neo Origins `neoorigins:*` and Dragon Survival have no Fabric version; MCA Reborn, Pixelmon and Starter Kit may use other ids on Fabric — to be set when Fabric mods are tested).

### 5.2 `LangConfig` (`world/serverconfig/DirectAuth-lang-<code>.json`)
Not in `assets/<modid>/lang/`. Messages are flat string fields on `LangConfig` (≈70 keys: `msgWelcome`, `errWrongPassword`, `msgAuthReminder`, `msgPremiumEnabled`, etc.). Resolved via `DirectAuth.getConfig().getLang().<field>`. Placeholders use `String.format` (`%d`, `%s`). Color codes use Minecraft `§` sequences.

Both `en` and `es` files are written on startup; the active one is selected by `ModConfig.language`. To add a language, add `LangConfig.load(dir.resolve("DirectAuth-lang-<code>.json"), "<code>")` in `ModConfig#load` and supply translations in `LangConfig` static blocks.

### 5.3 Runtime files (all under `<world>/serverconfig/`)

| File | Purpose |
|---|---|
| `DirectAuth-config.json` | `ModConfig` |
| `DirectAuth-lang-en.json`, `DirectAuth-lang-es.json` | `LangConfig` per language |
| `directauth.db` | SQLite user store |
| `DirectAuth_positions.json` | `PositionManager` checkpoints |
| `DirectAuth_users.json.MIGRATED` | Legacy JSON backup (created once) |

---

## 6. Build & Dev Workflow

All commands from repo root. Java 21 must be on `PATH` or via a Gradle toolchain provider.

```powershell
.\gradlew build                 # compile + remapped jar with jar-in-jar sqlite (build/libs/directauth-fabric-1.21.1-<version>.jar)
.\gradlew runServer             # dev server (run/ dir)
.\gradlew runClient             # dev client (DirectAuth is server-only, so it is not loaded there)
.\gradlew publish               # publish to local repo/ maven
.\gradlew --refresh-dependencies
```

- Mod metadata: `src/main/resources/fabric.mod.json`; `processResources` expands `${mod_id}`, `${mod_version}`, `${loader_version}`, etc. from `gradle.properties`.
- No unit tests, no Checkstyle/Spotless, no pre-commit hooks. The only CI is `.github/workflows/build.yml` running `./gradlew build` on Ubuntu.
- End-to-end smoke tests live in `tools/smoke-test/` (Linux, Node ≥ 22.5, Java 21): `cd tools/smoke-test && npm ci && ./run-all.sh` builds the mod (`build testmodJar`), installs a production Fabric 1.21.1 server (Fabric server launcher + Fabric API, versions from `gradle.properties`) in `runs/smoke-test-fabric/` the first time (needs `eula=true` there; `runs/smoke-test/` belongs to the other loaders' branches), runs it with the release jar (intermediary names: only that exercises the remapped mixins and the jar-in-jar) plus the test mod in `src/testmod/` (source set `testmod`, built and remapped by `testmodJar` into `build/testmod/`; never in the release jar), which stands in for other mods (Fabric API payloads both ways, Architectury-style one-type-per-message payloads, vanilla and `ExtendedScreenHandlerFactory` menus, a chat listener at the head of `handleChat`, first-join teleports from `ServerPlayConnectionEvents.JOIN` and after DirectAuth's join handler, `/datest` commands), and drives it with Mineflayer bots, RCON and SQLite checks (~5 min, exit code ≠ 0 on failure). Bots that declare mod channels do it like a Fabric client, with the common protocol (`c:version`/`c:register`) during configuration. The server's session host points to a fake session server on port 25580 that `premium()` in `lib.js` starts, so bots can join as premium. Run it after changing the auth flow, restrictions, Online Mode, admin commands or anything in `network/`, and add a test for new behavior. Not part of CI.
- Tests with real third-party mods live in `tools/mod-tests/` (NeoForge only for now; not ported to Fabric).
- Working dir for runs is `run/`. Logs and the dev world live there.

---

## 7. Conventions & Gotchas

### Code style
- Standard Java conventions: `PascalCase` classes, `camelCase` methods/fields, `UPPER_SNAKE_CASE` constants.
- Comments and existing string literals mix English and Spanish (the author's native language). User-visible strings belong in `LangConfig`, never inline.
- UTF-8 enforced at compile time (`options.encoding = 'UTF-8'`).

### Threading
- Anything touching `DatabaseManager` is async — always chain via `.thenAcceptAsync(..., AuthTasks.onServerThread(server))` to land mutations back on the server thread (plain `server` as executor runs the callback on the wrong thread during shutdown).
- `MojangAPI` callbacks must also be marshalled back to the server thread before touching player state.
- PBKDF2 costs ~50-150 ms per hash: never call `LoginManager.hashPassword` / `verifyPassword` on the server thread; use the `*Async` variants. For player commands, wrap the future with `AuthTasks.begin` / `AuthTasks.finish`, which also re-resolves the player (they may have respawned or disconnected by the time it completes).
- Player-state maps in `LoginManager` and `PlayerRestrictionHandler` are `ConcurrentHashMap` — preserve that when adding new ones.

### Singletons
- Don't `new` managers. Use `DirectAuth.getLoginManager()`, `getDatabaseManager()`, `getPositionManager()`, `getConfig()`.
- Initialization order matters: config and DB are wired in `SERVER_STARTED`, so don't touch them in `onInitialize`.

### Anti-bot expectations
When changing registration / login flow, preserve all of: `registrationDelay`, `maxAccountsPerIP` (checked atomically with the INSERT in `DatabaseManager#registerUserAsync`), grace-session IP binding, login cooldown, per-account max-attempts lockout (must not reset on reconnect), anchored-position enforcement, the `loginTimeout` kick, and the `network/` jail (held mod payloads, vanilla allowlist, deferred screens and menus). These are the security surface — regressions here are silent and dangerous.

### Online Mode and UUIDs
An account's UUID never changes with its mode (see §4.2). Don't reintroduce moving player files between UUIDs: mods with data in memory overwrite them, and every mod would need its own migration rules. Changing *how* a login gets the account's UUID belongs in `MixinServerLoginPacketListenerImpl`. The smoke test logs bots in as premium through a fake session server (`premium()` in `tools/smoke-test/lib.js`).

### Things that look like bugs but aren't
- The `assets/examplemod/lang/en_us.json` placeholder file is leftover from the NeoForge mod template on `main`; real strings live in `LangConfig`.
- `Documentation-NeoForge/` (NeoForged docs) and `Documentation/` (Forge docs) are git-ignored upstream docs, not project documentation. Don't `git add` them.

---

## 8. When You Change Things

- **Adding a command** → new class in `commands/`, register it from `DirectAuth#registerCommands` (called by `CommandRegistrationCallback`).
- **Adding a config field** → add field + default in `ModConfig`, the loader rewrites the JSON automatically on next start.
- **Adding a message** → add field in `LangConfig` with both English and Spanish defaults; reference via `getConfig().getLang().<field>`. Existing language files keep the old text of a key, so when a message's meaning changes, rename its key.
- **Adding a persistent column** → migrate the SQLite schema in `DatabaseManager#initDatabase` (ALTER TABLE with a try/catch on `SQLITE_ERROR` is the existing pattern), **bump `SCHEMA_VERSION`** so existing databases are backed up automatically before the change, and add the field to `UserData`. Keep the change readable by older versions (they ignore extra columns) and check it with `DOWNGRADE_REF=<previous release> ./run-all.sh` in `tools/smoke-test/`.
- **Adding a restriction** → register a Fabric API callback in `PlayerRestrictionHandler#register` (or, if there is none, add a mixin at the point where NeoForge fires the event on `main` that calls an `on*` method there) and gate it on `PlayerRestrictionHandler#isRestricted` (which already excludes `FakePlayer`s). Add a check to the jail test in `tools/smoke-test/tests.js`, with a positive control after `/login` so the test cannot pass by accident.

Keep this file in sync with what's actually in the code. If you make an architectural change that contradicts a section here, update the section in the same PR.
