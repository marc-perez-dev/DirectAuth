# AGENTS.md — DirectAuth

Single source of truth for any AI coding agent working in this repo (Claude Code, Gemini, Codex, Cursor, Aider, etc.). Keep this file up to date when architecture changes; do not maintain parallel `CLAUDE.md` / `GEMINI.md` copies.

---

## 1. Project Overview

**DirectAuth** is a server-side Minecraft authentication mod for **Forge 1.20.1** (Forge 47.x, Java 17). This is the `forge-1.20.1` branch, a port of the NeoForge 1.21.1 version on `main` (built for the Biohazard: Project Genesis modpack, Forge 47.4.0); keep behavior identical to `main` and port fixes between the branches. It lets a server run in offline mode safely by forcing players to `/register` and `/login` before they can play, with optional opt-in conversion to premium (online-mode) accounts that log in automatically through Mojang. An account keeps the UUID it plays with when it changes mode, so no player data ever moves.

- **Mod ID:** `directauth`
- **Main class:** `com.marcp.directauth.DirectAuth`
- **License:** MIT
- **Side:** server-only. Clients don't have it installed: `displayTest="IGNORE_SERVER_VERSION"` in `mods.toml` and no network channels, so Forge's handshake never asks for it.

The `Documentation/` directory in the repo root is **not part of this mod** — on this branch it is the upstream Forge docs for 1.20.1 (`MinecraftForge/Documentation`, branch `1.20.1`) cloned for reference and is git-ignored (`main` also ignores `Documentation-NeoForge/`, the NeoForged docs). Do not treat them as production code or modify them as part of mod work.

---

## 2. Tech Stack & Versions

Authoritative values live in `gradle.properties`; the table below is a quick reference.

| Item | Value |
|---|---|
| Minecraft | `1.20.1` (range `[1.20.1,1.20.2)`) |
| Forge | `47.4.0` (range `[47.4.0,)`, the version it is built and tested against) |
| Java | 17 (toolchain pinned) |
| Loader | `javafml`, range `[47,)` |
| Parchment mappings | `2023.09.03` for MC `1.20.1` |
| Gradle plugin | `net.neoforged.moddev.legacyforge` `2.0.121` (ModDevGradle for Forge 1.17–1.20.1) |
| Persistence | SQLite via `org.xerial:sqlite-jdbc:3.46.0.0` (jar-in-jar) |
| Gradle wrapper | 9.2.0 |

Mixins are enabled (`directauth.mixins.json`, declared through the `MixinConfigs` manifest attribute — Forge 1.20.1 ignores `[[mixins]]` in `mods.toml`). Production Forge 1.20.1 runs on SRG names: `build` reobfuscates the jar (`reobfJar`) and the Mixin annotation processor writes `directauth.refmap.json`, so a mixin target that only works in dev is a real bug — test with the production jar (the smoke test does). One access transformer (`META-INF/accesstransformer.cfg`) makes `ServerLoginPacketListenerImpl$State` public for the login mixin (the decompiled sources show it already public because the AT is applied to them). No coremods.

---

## 3. Repo Layout

```
DirectAuth/
├── build.gradle              # moddev legacyforge plugin, runs, mixin AP/refmap, jarJar(sqlite-jdbc)
├── gradle.properties         # all versions & mod metadata
├── settings.gradle           # plugin management
├── src/
│   ├── main/
│   │   ├── java/com/marcp/directauth/
│   │   │   ├── DirectAuth.java       # @Mod entry — registers handlers, exposes singletons
│   │   │   ├── auth/                 # LoginManager, AuthTasks, MojangAPI, MojangVerifiedConnection, ConfirmationManager
│   │   │   ├── commands/             # Brigadier command classes
│   │   │   ├── config/               # ModConfig, LangConfig (Gson JSON)
│   │   │   ├── data/                 # DatabaseManager (SQLite), PositionManager, UserData, StoredLocation
│   │   │   ├── events/               # ConnectionHandler, PlayerRestrictionHandler
│   │   │   ├── network/              # JailTraffic (other mods' packets and menus while jailed), JailPackets (vanilla allowlist)
│   │   │   └── mixin/                # login handshake and account UUID, packet restrictions, respawn, effects, RCON response, accessors
│   │   ├── resources/
│   │   │   ├── META-INF/             # accesstransformer.cfg; mods.toml is generated from templates/
│   │   │   ├── pack.mcmeta           # required by Forge 1.20.1 for the mod's resource/data pack
│   │   │   ├── directauth.mixins.json
│   │   │   └── assets/directauth/    # (lang/ here is placeholder — real i18n is JSON in worldserverconfig)
│   │   └── templates/META-INF/mods.toml  # source for mods.toml expansion
│   └── generated/resources/  # output of `runData`
├── Documentation/            # GIT-IGNORED — upstream Forge 1.20.1 docs, not part of this mod
├── tools/smoke-test/         # end-to-end tests: production Forge server + Mineflayer bots (see its README)
├── tools/mod-tests/          # end-to-end tests with real third-party mods: production Forge server and client under Xvfb (see its README)
├── run/  runs/  run-data/    # gitignored dev runtime dirs (the smoke test uses runs/smoke-test/)
├── build/  .gradle/  repo/   # gitignored gradle output
├── .github/workflows/build.yml  # CI: gradlew build on push/PR (Ubuntu, Temurin 17)
├── AGENTS.md                 # ← you are here
└── README.md
```

---

## 4. Source Architecture

### 4.1 Entrypoint — `DirectAuth.java`
- `@Mod("directauth")` no-arg constructor.
- Registers `ConnectionHandler` and `PlayerRestrictionHandler` on `MinecraftForge.EVENT_BUS`, and registers commands via `RegisterCommandsEvent`.
- Holds static singletons exposed via getters: `LoginManager`, `DatabaseManager`, `PositionManager`, `ModConfig`. **All cross-package access goes through these getters** — there is no DI container.
- `initDatabase(Path)` and `initConfig(Path)` are called by `ConnectionHandler#onServerStarted`, *not* in the constructor (they need the world path). `initConfig` returns `false` (keeping the previous config, or defaults on first start) if the JSON is unreadable; it never overwrites a malformed file.
- **One `LoginManager` per server instance**: `resetLoginManager()` runs on `ServerStartedEvent`, and `ServerStoppedEvent` shuts it down and closes the DB (`closeDatabase()`). Singleplayer starts several servers in one JVM, so never keep auth state in statics that survive a stop.

### 4.2 `auth/`
- **`LoginManager`** — central authentication state. Thread-safe (`ConcurrentHashMap`). Holds `authenticatedPlayers`, `failedLogins` + `loginAttempts` (**keyed by lowercased account name + IP**, not UUID: they survive reconnects and cover case variants, but a stranger on another IP cannot lock the owner out; failures count within max(15 min, `loginLockoutSeconds`), the cooldown entry expires after `loginCooldownMs`; `clearFailedLogins(name)` lifts a lockout), `connectionTimes` (login timeout), `preLoginCache` (mixin-populated; `NULL_MARKER` = "not registered"; entries expire after 10 min), `graceSessions` (IP-locked re-login, invalidated per account with `invalidateSessions(name)`), `pendingOperations` (one async password operation per player). Runs a scheduler for cleanup at `sessionCleanupInterval` minutes and a 2-thread hash pool.
- **Password hashing is PBKDF2WithHmacSHA256**, 100k iterations, 256-bit key, stored as `salt:hash` (Base64) — **the format 1.1/1.2 read (they assume 100k), so downgrading keeps logins working. Don't change it** (raising iterations needs a self-describing format and breaks downgrades). `verifyPassword` also accepts 10k `salt:hash` hashes from 1.0.0 and `iterations:salt:hash` from pre-release builds, returning `OK_NEEDS_REHASH`; `/login` then rewrites them as 100k `salt:hash`. Never hash on the server thread — use `hashPasswordAsync` / `verifyPasswordAsync`.
- **`AuthTasks`** — `begin(player)` reserves the player's single in-flight operation; `finish(...)` brings a future back to the server thread, re-resolves the player (same connection only) and reports failures (including exceptions thrown by the callback) with `errInternal`. `onServerThread(server)` is the executor to use instead of `server`: once the server is stopping, `server.execute` would run the task inline on the DB/hash/HTTP thread, so it drops it instead. `revokeAccountAccess(...)` invalidates grace sessions and kicks **every** case variant of an account. `finish(CommandSourceStack, ...)` (admin commands) registers operations started from RCON so the RCON response waits for their result (see `MixinRconConsoleSource`).
- **Online Mode keeps the account's UUID.** `/online` and `/directauth online` only write the mode columns (`setOnlineModeAsync`, never the password), and the player stays connected. Enabling stores the UUID the player is playing with as `playerUUID`; `MixinServerLoginPacketListenerImpl` then gives every login of the account that UUID, also the Mojang-verified ones (with the Mojang skin). **Never move player files between UUIDs**: most mods keep per-player data in memory, write it back under the old UUID and treat the new UUID as a new player (Starter Kit replaced a whole inventory with its kit, FTB Quests/Teams started over). Accounts made premium before 1.3.1 had their data migrated to the Mojang UUID, and keep that UUID (`playerUUID` = `onlineUUID` once switched back to offline).
- **`MojangVerifiedConnection`** — implemented by `Connection` (`MixinConnection`): the Mojang UUID the session server confirmed during the login of that connection. `ConnectionHandler` auto-authenticates a premium account only if it equals `onlineUUID` — the player's UUID proves nothing, since the account plays with its own.
- **`MojangAPI`** — non-blocking UUID lookup (`HttpClient.sendAsync`, 5s connect / 10s request timeout) against `api.mojang.com/users/profiles/minecraft/{name}`. Only names matching `[A-Za-z0-9_]{3,16}` are ever sent (offline names may contain `#`, `?`, `/`… which would change the URL), and the returned profile name must match. Completes with `null` if the account does not exist and **exceptionally** if Mojang is unreachable. Callbacks must be marshalled back to the main thread.
- **`ConfirmationManager`** — pending-action store (30s TTL, `Consumer<ServerPlayer>`) used by `/changepassword` and `/unregister`, drained by `/directauth confirm`; cleared on logout and server stop.

### 4.3 `commands/`
All Brigadier-based. Registered through `RegisterCommandsEvent` in `DirectAuth`.

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
- **`ConnectionHandler`** — `ServerStartedEvent` (fresh `LoginManager`, init DB/config/scheduler), `ServerStoppedEvent` (close DB, shut down `LoginManager`, clear anchors/confirmations; runs *after* players are disconnected), `PlayerLoggedInEvent` (consume mixin pre-cache, attempt grace-session restore only if the account exists; otherwise a player who has played before is teleported to spawn with the original position saved, while a never-played player stays where the game or a first-join mod put them; the anchor is reset either way), `PlayerLoggedOutEvent` (pause session if authenticated, else drop; discards what `JailTraffic` held for that connection).
- **`PlayerRestrictionHandler`** — enforces the unauthenticated-player jail. `isRestricted(entity)` is the single check (real `ServerPlayer`, **not a `FakePlayer`**, not authenticated) — mixins use it too. Anchors players with a `Map<UUID, Anchor(dimension, Vec3)>` from `TickEvent.ServerTickEvent` (END), iterating the connected players that are still ticked by their level (`!isRemoved()`, like `EntityTickEvent` on `main`; teleports back across dimensions if needed). **Not** `PlayerTickEvent`: it fires inside `ServerPlayer.doTick()`, after which the connection resets the player to its start-of-tick position and would undo the anchor teleport. It also cancels block break, left/right click, entity interact (incl. `EntityInteractSpecific` for armor stands), item pickup (`EntityItemPickupEvent`), XP pickup, mount, attack, all chat (a fallback: `JailPackets.dropChat` already drops it; commands are filtered separately in `CommandEvent`: only `register`/`login`/`online`), damage (`LivingHurtEvent` amount set to 0, before absorption hearts are spent — `LivingDamageEvent` fires after that in 1.20.1), and healing. Item drops and inventory/container clicks are blocked at the packet level by `MixinServerGamePacketListenerImpl` — **don't** handle `ItemTossEvent`: the game itself fires it for drops (closing menus on disconnect, `/give`). `release(player)` removes the anchor, resyncs effects and calls `JailTraffic.release`; it runs after `/login` and `/register`, and from `ConnectionHandler` for auto-login and restored sessions. Sends the auth reminder every ~5 seconds (100 ticks). Kicks if `loginTimeout` elapsed.

### 4.5 `network/` — the jail and other mods
Many mods act at first join or accept client packets without knowing about authentication. Everything here keys off `PlayerRestrictionHandler.isRestricted`.
- **Payload types on Forge.** A mod usually sends all its messages through one `SimpleChannel`, with the message's registration index in the first byte. `JailTraffic` calls a payload's type `channel#index` for channels created as `SimpleChannel` (`MixinSimpleChannel` records them; Pixelmon has its own copy with a 2-byte index, recorded by `MixinPixelmonSimpleChannel`), `architectury:network#<id>` for Architectury's single channel (each message starts with its own id: FTB Quests, FTB Teams, KubeJS…), and `channel` for the rest. Config entries are either a type or a whole channel. Payloads of the `minecraft`, `forge` and `fml` namespaces (channel registration, brand, Forge's own) are never held or deferred.
- **`JailTraffic`** — per jailed connection, until it authenticates:
  - **Payloads the client sends to mods** are held (only the last of each payload type, in arrival order) and processed through `NetworkHooks.onCustomPayload` on `release` (their buffers are retained while held and released afterwards, or when dropped); they are dropped if the connection leaves first. This stops an impostor from acting for the account (Cobblemon releases, Vampirism bites, choosing an origin) while one-shot handshakes (Simple Voice Chat's secret request) still work after `/login`. Holding instead of dropping, and keeping only the last per type, are deliberate: Epic Fight sends a packet every tick. The client chooses the types (any channel name, any Architectury id), so a connection holds at most 64 types and 1 MiB; what does not fit is dropped (logged once). Architectury's `SplitPacketTransformer` sends the fragments of a big message with the same id, so a split message held from the jail loses all but its last fragment (client messages only split above 32 KB; no mod in the target modpack uses it). `heldPayloadExceptions` lists payloads that may pass (a mod's timed "I have the mod" reply).
  - **Screen-opening payloads** the server sends (`deferredScreenPayloads`) are delivered on `release`, last of each type. Only top-level custom payloads are checked: in 1.20.1 neither Forge nor vanilla puts mod payloads in a `ClientboundBundlePacket` (NeoForge 1.21.1 does, and `main` splits them out) (Pixelmon resends every 5 s).
  - **Menus the server opens** (`ServerPlayer.openMenu` and Forge's `NetworkHooks.openScreen`, which does not go through it; a jailed player cannot open one themselves) are opened on `release` the same way they were requested (Tensura's race menu). `openMenu`'s caller gets `OptionalInt.empty()`, so anything it would send keyed by the container id is not replayed (merchant offers do this, but trading needs an interaction the jail blocks).
  - A deferred screen is delivered even if the client already acted on the mod while jailed, because DirectAuth cannot tell it became stale (Pixelmon's client can show its starter screen in the jail from a previous session; after `/login` the screen reopens once and one more pick closes it).
  - Netty threads add, the server thread releases: each connection's queue has its own lock, and adding re-checks under it that the connection is still open, jailed and not yet released (otherwise the packet takes its normal path). A payload that arrives after authentication but before `release` drops the held one of the same type, which is older.
- **`JailPackets`** — allowlist of vanilla serverbound packets a jailed player may send; the rest are dropped in `MixinConnection`. Chat messages (not commands) are dropped there too (`dropChat`), before any mod reads them (MCA's villager AI hooks the start of `handleChat` and sends the text to an external service); they still go through vanilla `handleChat`'s validation without being broadcast (illegal characters, order, last-seen acknowledgements, and the signed-message chain, which would otherwise fall behind the client's and kick the player at their next message after `/login`) and counted by vanilla's chat spam kick, and the player gets `msgUseCommands` (unless `/login` finished in between). Some check permissions themselves (command blocks, difficulty, structure blocks, NBT queries) and an op impostor has those permissions. Interaction packets stay allowed because the jail's events cancel them *and* correct the client.

### 4.6 `mixin/`
- **`MixinServerLoginPacketListenerImpl`** — async pre-load of `UserData` during the login handshake so neither the handshake nor `PlayerLoggedInEvent` blocks on SQLite. Phase 2 only *peeks* the cache. For premium accounts it does what vanilla does with `online-mode=true` (sets `gameProfile` to a name-only profile, state `KEY`, sends `ClientboundHelloPacket`); vanilla `handleKey` then verifies the session with Mojang. Exceptions from re-running vanilla `handleHello` inside the future disconnect the client instead of being swallowed. At the head of `handleAcceptedLogin` (server thread, after Forge's negotiation, before vanilla builds the offline profile with `createFakeProfile`) it records a Mojang-confirmed UUID on the connection (`MojangVerifiedConnection`: after DirectAuth's premium handshake, or vanilla's on online-mode and LAN servers; not the singleplayer offline fallback, which leaves the profile without a UUID) and swaps the profile for one with the account's UUID (`UserData#getAccountUUID` of the account read in phase 2, kept on the listener because the cache entry can be gone by then; keeping the skin properties): for premium accounts only when Mojang confirmed `onlineUUID`, for offline accounts on the offline path (every case variant of the name then shares that UUID, like the exact name already did). Not on online-mode servers. `ConnectionHandler` disconnects a join that did not get the account's UUID (e.g. the cache entry was dropped mid-login), since mods would treat it as a new player.
- **`MixinLivingEntityEffects`** — cancels `tickEffects` for restricted players so effect durations freeze while in the jail (the client HUD is hidden/resynced by `PlayerRestrictionHandler`).
- **`MixinPlayerListRespawn`** + **`MixinServerPlayerRespawn`** — in 1.20.1 the respawn point lookup lives in `PlayerList.respawn`: for restricted players its `BlockPos` is replaced by `null` (no lookup, no anchor charge, no "no bed" message → world spawn), and `ServerPlayer.restoreFrom` copies the respawn point to the new player (vanilla 1.20.1 only sets it when it was used), so the account keeps its bed/anchor. After `/login`, `LoginCommand` runs the same lookup as `PlayerList.respawn` (`Player.findRespawnPositionAndUseSpawnBlock`, now unrestricted) so anchor charges and invalid spawn points follow vanilla rules; without a usable respawn point it places the player at world spawn with vanilla's `fudgeSpawnLocation` (`ServerPlayerAccessor`).
- **`MixinServerGamePacketListenerImpl`** — cancels drop actions, offhand swaps (F), pick-item (middle click), container clicks and creative-slot packets for restricted players (injected *after* `PacketUtils.ensureRunningOnSameThread`, i.e. on the server thread) and resyncs the client inventory.
- **`MixinItemPhysicPackets`** — `@Pseudo` compat mixin (`remap = false`, `require = 0`, no-op without the mod) on ItemPhysic's `DropPacket` / `PickupPacket#executeServer`: with `throw.enabled` the client sends Q as a CreativeCore packet instead of `ServerboundPlayerActionPacket`, which drops the item without passing through `handlePlayerAction`. Cancels both for restricted players (resyncs the inventory, `msgNoDrop` on drop). CreativeCore runs them on the server thread (`enqueueWork`).
- **`MixinRconConsoleSource`** — vanilla reads the RCON response as soon as the command returns, before async admin commands finish. Before `getCommandResponse()` the RCON thread (never the server thread) waits, up to 30 s, for the operations registered by `AuthTasks.finish`; `prepareForCommand()` forgets operations of previous commands. Admin commands must go through `AuthTasks.finish(CommandSourceStack, ...)` for RCON to see their output.
- **`ServerGamePacketListenerAccessor`** — invokers for the private steps of vanilla `handleChat`, used by `JailPackets.dropChat`. **`ClientboundCustomPayloadPacketAccessor`** — the payload's buffer without the full copy `getData()` makes in 1.20.1.
- **`ServerPlayerAccessor`** — invoker for the private `ServerPlayer.fudgeSpawnLocation(ServerLevel)` (1.21.1's `adjustSpawnLocation`), used by `LoginCommand` to place a player safely at world spawn.
- **`MixinServerGamePacketListenerCustomPayload`** — `handleCustomPayload` (network thread, before Forge's `NetworkHooks.onCustomPayload`): holds mod payloads from jailed players via `JailTraffic`.
- **`MixinConnection`** — `genericsFtw` (every received packet, network thread): drops vanilla packets of jailed players that `JailPackets` does not allow. `send`: defers screen payloads via `JailTraffic` (Forge's `PacketDistributor` sends straight through the `Connection`, not `ServerGamePacketListenerImpl.send`). Also implements `MojangVerifiedConnection`.
- **`MixinServerPlayerOpenMenu`** / **`MixinNetworkHooksOpenScreen`** — defer `ServerPlayer.openMenu(MenuProvider)` and `NetworkHooks.openScreen(ServerPlayer, MenuProvider, Consumer)` (the other `openScreen` overloads delegate to it) for jailed players via `JailTraffic`.
- **`MixinSimpleChannel`** — records which channels are `SimpleChannel`s, so `JailTraffic` can tell their message types apart.
- **`MixinPixelmonSimpleChannel`** — `@Pseudo` compat mixin (`remap = false`, `require = 0`) on Pixelmon's own `SimpleChannel` copy, which writes a 2-byte index: records its channel the same way.

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
Gson, pretty-printed. Loaded once on `ServerStartedEvent`; missing fields are filled with defaults and the file rewritten.

| Group | Fields (defaults) |
|---|---|
| General | `language` (`"en"`) |
| Session | `sessionGracePeriod` (600s), `sessionCleanupInterval` (10 min) |
| Password | `minPasswordLength` (4), `maxPasswordLength` (32) |
| Login flood | `maxLoginAttempts` (5), `loginCooldownMs` (3000), `loginLockoutSeconds` (300), `loginTimeout` (60s) |
| Anti-bot | `registrationDelay` (1s), `maxAccountsPerIP` (5) |
| Other mods | `deferredScreenPayloads` (Origins, MCA, Pixelmon and Starter Kit screen payloads; entries are `channel#index` types or whole channels, see §4.5), `heldPayloadExceptions` (Starter Kit's timed reply, TACZ's clock sync and Architectury's `sync_ids` reply), `logJailPayloads` (false; logs the payload types jailed players receive, to find a new mod's screen), `otherModsDefaultsVersion`. Built-in ids live in `ModConfig.DEFAULT_SCREEN_PAYLOADS` / `DEFAULT_HELD_EXCEPTIONS` with the version that added them; bump `OTHER_MODS_DEFAULTS` when adding one so existing configs get it on load (ids an admin removed later don't come back). |

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

All commands from repo root. Java 17 (or newer to run Gradle; the build uses a Java 17 toolchain, downloaded by the foojay resolver if missing).

```powershell
.\gradlew build                 # compile + reobfuscated jar with jar-in-jar sqlite (build/libs/directauth-forge-1.20.1-<version>.jar)
.\gradlew runClient             # dev client (run/ dir, DEBUG logs, REGISTRIES marker)
.\gradlew runServer             # dev server
.\gradlew runData               # data generation → src/generated/resources/
.\gradlew publish               # publish to local repo/ maven
.\gradlew --refresh-dependencies
```

- Mod metadata is **generated**: edit `src/main/templates/META-INF/mods.toml`. The `generateModMetadata` task expands `${mod_id}`, `${mod_version}`, `${forge_version_range}`, etc. (it is a Groovy template: a literal `${` anywhere in the file, comments included, breaks it). Values come from `gradle.properties`; the task runs on IDE sync.
- No unit tests, no Checkstyle/Spotless, no pre-commit hooks. The only CI is `.github/workflows/build.yml` running `./gradlew build` on Ubuntu (Temurin 17).
- End-to-end smoke tests live in `tools/smoke-test/` (Linux, Node ≥ 22.5): `cd tools/smoke-test && npm ci && ./run-all.sh` builds the mod, installs a production Forge 1.20.1-47.4.0 server in `runs/smoke-test/` the first time (needs `eula=true` there), runs it with the reobfuscated jar in `mods/` and drives it with Mineflayer bots, RCON and SQLite checks (~4 min, exit code ≠ 0 on failure). Run it after changing the auth flow, restrictions, Online Mode or admin commands, and add a test for new behavior. Not part of CI.
- Tests with real third-party mods live in `tools/mod-tests/` (Linux, `xvfb-run`, ~6 GB RAM): `cd tools/mod-tests && node run.js [scenario...]` runs a production Forge server with DirectAuth and the mods of each scenario, and a production Forge client under Xvfb driven by the `directauth_modtests` harness (separate Gradle build; Forge server and client installed in `runs/mod-tests/` the first time; mods pinned with sha512 in `mods.json`). One scenario per mod: screens at first join, packets an impostor could send, first-join teleports. Run it before a release and after changing anything in `network/` or the jail. Not part of CI.
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
- Initialization order matters: config and DB are wired in `ServerStartedEvent`, so don't touch them in the mod constructor.

### Anti-bot expectations
When changing registration / login flow, preserve all of: `registrationDelay`, `maxAccountsPerIP` (checked atomically with the INSERT in `DatabaseManager#registerUserAsync`), grace-session IP binding, login cooldown, per-account max-attempts lockout (must not reset on reconnect), anchored-position enforcement, the `loginTimeout` kick, and the `network/` jail (held mod payloads, vanilla allowlist, deferred screens and menus). These are the security surface — regressions here are silent and dangerous.

### Online Mode and UUIDs
An account's UUID never changes with its mode (see §4.2). Don't reintroduce moving player files between UUIDs: mods with data in memory overwrite them, and every mod would need its own migration rules. Changing *how* a login gets the account's UUID belongs in `MixinServerLoginPacketListenerImpl`. The smoke test logs bots in as premium through a fake session server (`premium()` in `tools/smoke-test/lib.js`).

### Things that look like bugs but aren't
- The `assets/examplemod/lang/en_us.json` placeholder file is leftover from the NeoForge mod template; real strings live in `LangConfig`.
- `Documentation/` (Forge docs) and, on `main`, `Documentation-NeoForge/` (NeoForged docs) are git-ignored upstream docs, not project documentation. Don't `git add` them.

---

## 8. When You Change Things

- **Adding a command** → new class in `commands/`, register it from `DirectAuth#registerCommands` (called by `RegisterCommandsEvent`).
- **Adding a config field** → add field + default in `ModConfig`, the loader rewrites the JSON automatically on next start.
- **Adding a message** → add field in `LangConfig` with both English and Spanish defaults; reference via `getConfig().getLang().<field>`. Existing language files keep the old text of a key, so when a message's meaning changes, rename its key.
- **Adding a persistent column** → migrate the SQLite schema in `DatabaseManager#initDatabase` (ALTER TABLE with a try/catch on `SQLITE_ERROR` is the existing pattern), **bump `SCHEMA_VERSION`** so existing databases are backed up automatically before the change, and add the field to `UserData`. Keep the change readable by older versions (they ignore extra columns) and check it with `DOWNGRADE_REF=<previous release> ./run-all.sh` in `tools/smoke-test/`.
- **Adding a restriction** → add a `@SubscribeEvent` method in `PlayerRestrictionHandler` and gate it on `PlayerRestrictionHandler#isRestricted` (which already excludes `FakePlayer`s). Add a check to the jail test in `tools/smoke-test/tests.js`, with a positive control after `/login` so the test cannot pass by accident.

Keep this file in sync with what's actually in the code. If you make an architectural change that contradicts a section here, update the section in the same PR.
