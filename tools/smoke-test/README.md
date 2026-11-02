# DirectAuth smoke tests

End-to-end tests against a real server. `run-all.sh` builds the mod from this checkout (`gradlew
build testmodJar`), starts a **production Fabric 1.21.1 server** (Fabric Loader and Fabric API, the
versions in `gradle.properties`; offline mode, flat world) in `runs/smoke-test-fabric/` with the
release jar (remapped to intermediary, so the mixins are checked as players get them) and the test
mod in `mods/`, and drives it with
[Mineflayer](https://github.com/PrismarineJS/mineflayer) bots, RCON and direct checks on the
SQLite database and the world files. Bots connect from different loopback addresses
(`127.0.0.x`) to simulate players on different IPs.

## Requirements

- Linux (uses the `127.0.0.0/8` loopback range, `mkfifo` and `setsid`).
- Java 21 on `PATH` (or `JAVA21=/path/to/java`). The first start downloads the Fabric server
  launcher and Fabric API into `runs/smoke-test-fabric/`, and the launcher downloads the vanilla
  server and libraries (internet access, about a minute). `runs/smoke-test/` belongs to the
  NeoForge and Forge branches and is not touched.
- Node.js 22.5 or newer (`node:sqlite`).
- Ports 25565 and 25575 free on `127.0.0.1`.
- Internet access: the premium tests query the Mojang API with the real account names `Notch` and
  `jeb_` (lookup only, nobody logs into those accounts).
- Port 25580 free on `127.0.0.1`: `lib.js` serves a fake Mojang session server there
  (`premium()`), and `server.sh` points the server at it (`minecraft.api.session.host`), so bots
  without a Mojang account can join as premium players.
- The Minecraft EULA accepted for the test server. Read https://aka.ms/MinecraftEULA, then:
  `mkdir -p runs/smoke-test-fabric && echo eula=true > runs/smoke-test-fabric/eula.txt`

## Usage

```bash
cd tools/smoke-test
npm ci
./run-all.sh          # wipes runs/smoke-test-fabric/world; exit code != 0 if anything fails
```

A full run takes about 5 minutes. Before a release, also check that going back to the previous
version still works (players keep logging in on a database written by this version):

```bash
DOWNGRADE_REF=<ref> ./run-all.sh   # a Fabric release tag or commit of this branch
```

It builds that ref in a temporary `git worktree`, runs it against the same world, logs in with
accounts written by this version, then starts this version again. The ref must be a Fabric build of
DirectAuth (`run-all.sh` checks for `src/main/resources/fabric.mod.json` and Fabric Loom before
running anything); `main` is NeoForge, and there is no Fabric release to downgrade to yet.

To work on a single test against the current world:

```bash
./up.sh               # start the server (keeps the world)
node tests.js 'lockout'   # regex on test names
./down.sh
```

Tests assume a fresh world. When re-running a test against a world that already has its
accounts, some tests take fresh names from the environment: `JAIL`, `JAIL_IP`, `DEAD`, `DEAD_IP`,
`DEAD_IP2`, `NOBED`, `PICK`, `HURT`, `RIDER`. The tests for other mods append `FRESH` to their names
(`FRESH=2 node tests.js 'menus'`).

## Other mods (test mod `directauth_test`)

The server also loads a small Fabric test mod, `src/testmod/` in this repo (built by `gradlew
testmodJar` into `build/testmod/`, remapped like the release jar, never in the DirectAuth jar). It
stands in for other mods so the bots can check how DirectAuth treats them:

- Payloads in both directions registered with Fabric API (`PayloadTypeRegistry`,
  `ServerPlayNetworking`): `directauth_test:action`, `hello` (client to server), `screen`, `data`
  (server to client, sent only if the client declared the channel, `ServerPlayNetworking.canSend`).
  `join(name, ip, { channels: [...] })` declares play channels like a Fabric client does, with the
  common protocol during configuration (`c:version`, `c:register`); `registerChannels(bot, [...])`
  sends `minecraft:register` in game.
- Messages the way Architectury 13 sends them on Fabric 1.21.1 (`NetworkAggregator`): one payload
  type per message id, its content the encoded message as a byte array (`sendArchPayload`):
  `directauth_test:arch_claim`, `arch_team`, and `junk0`…`junk69` to fill the held-payload cap.
- `/datest log <player>`: what the server received from that player (payloads, menu button clicks,
  menus created, chat read at the start of `handleChat` like MCA).
- `/datest screen|data <player> <value>`, `/datest menu <player> <tag>` (vanilla `openMenu`),
  `/datest xmenu <player> <tag>` (Fabric API `ExtendedScreenHandlerFactory`, opened on the client by
  the `fabric-screen-handler-api-v1:open_screen` payload): send a payload or open a menu from the
  server.
- Players whose names start with `Scr`, `MenuLogin` or `XMenuLogin` get a screen payload or a menu
  from `ServerPlayConnectionEvents.JOIN`; new players named `TpFirst…` are teleported to
  100.5 -60 100.5 from `JOIN` (mid `placeNewPlayer`, before DirectAuth's join handler) and
  `TpLast…` from a mixin at the end of `placeNewPlayer`, after DirectAuth's.

`server.properties` enables command blocks so the tests can check that a jailed op impostor cannot
rewrite them.

## Files

| File | What it does |
|---|---|
| `tests.js` | Tests against a running server (registration, login, lockout, jail, respawn, sessions, admin commands, Online Mode, legacy hashes, config, RCON, races, jail damage, healing, XP, attacks, vehicles on rejoin, other mods' packets and menus, held-payload cap and order, channel registration, vanilla op packets). |
| `restart.js` | Phases that need restarts: shutdown with a `/login` in flight, positions surviving a restart, automatic database backup when upgrading an older schema, corrupt `positions.json`, legacy `DirectAuth_users.json` import, malformed config at startup, downgrade and re-upgrade, Turkish locale. |
| `lib.js` | Helpers: bots, message expectations, RCON, SQLite, player state, test-mod payloads. |
| `server.sh` | Installs (first time) and starts the production Fabric server in `runs/smoke-test-fabric/` with the release jar and the test mod, without GUI, with the console read from the `console` FIFO. `./server.sh install` only installs. |
| `common.sh`, `run-all.sh`, `restart-run.sh`, `up.sh`, `down.sh` | Orchestration. |

`server.out` (current server log), `server-all.out` (all restarts of the last run) and
`server-prev.out` are written next to the scripts and ignored by git.

## Expected errors in the server log

Some tests provoke errors on purpose; `run-all.sh` prints them at the end:

- `Could not read ... DirectAuth-config.json`, `Could not read saved
  positions`, `Skipping malformed legacy user entry`: the corrupt-file tests.
- `Too many mod payloads from unauthenticated ModCap`, `Invalid UUID 'abc'`, `BadUuid joined as
  ...`: the held-payload cap and the account-UUID tests.
- `<name> moved too quickly!` (vanilla): the jail and restored positions teleport players.
- With `DOWNGRADE_REF`, whatever the previous version logs about the newer database (it is
  started on purpose against data written by this version).

## Not covered

- Premium auto-login against Mojang's real session server (the fake one answers like it).
- Singleplayer / integrated server.
- Mojang API unreachable (`errMojangUnavailable`).
- Real third-party mods (`FakePlayer` machines, real FTB Quests data; FTB files are simulated). The
  test mod above simulates how other mods talk to the server.

Mineflayer is a protocol client, not the vanilla client: for example it does not roll back a
predicted block break when the server acknowledges the sequence, so tests do not rely on the
bot's view of a block it failed to break.
