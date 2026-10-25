# DirectAuth tests with real mods

End-to-end tests against real third-party mods. `run.js` starts a production Forge 1.20.1 server with
DirectAuth and the mods of one scenario, then a **real Forge client** (a production one, as players
run it) with the same mods, headless under Xvfb. The client runs a scripted scenario (`src/main/java/.../scenarios/`) through a small harness
mod, `directauth_modtests`, and checks the result over RCON.

The smoke test (`tools/smoke-test/`) covers DirectAuth itself with Mineflayer bots. Mineflayer is a
vanilla client, though, and cannot join a server with these mods. This suite checks how the jail
interacts with them: screens a mod opens at first join, packets a jailed impostor could send to a
mod, and first-join teleports.

## Requirements

- Linux with `xvfb-run` (Fedora: `xorg-x11-server-Xvfb`) and Mesa (software OpenGL is enough).
- Node.js 18 or newer (no npm dependencies).
- About 6 GB of free RAM: the server and the client run at the same time (Pixelmon gets 3 GB each).
- Internet access the first time: the mods (about 1 GB) are downloaded into `build/mod-cache/` and
  checked against the sha512 in `mods.json`, and the Forge server and client (with Minecraft's
  libraries and assets, about 1 GB more) are installed in `runs/mod-tests/`.
- The Minecraft EULA accepted, either for the smoke test (`runs/smoke-test/eula.txt`) or here:
  `mkdir -p runs/mod-tests && echo eula=true > runs/mod-tests/eula.txt` (from the repo root).
- Ports 25570 (game), 25580 (RCON) and 24454 (Simple Voice Chat, UDP) free on `127.0.0.1`.
- Java 17: the one Gradle's toolchain downloaded into `~/.gradle/jdks`, or `JAVA17=/path/to/java`.

## Usage

```bash
cd tools/mod-tests
node run.js                  # every scenario in mods.json
node run.js cobblemon mca    # some of them
```

To test a given DirectAuth jar (a release, or the code before a change) instead of building this
checkout: `DIRECTAUTH_JAR=path/to/directauth.jar node run.js ...`.

Each scenario starts from a fresh world in `runs/mod-tests/<scenario>/` and takes a few minutes. The
server and client logs stay there (`server.log`, `client.log`), next to `results.jsonl`. Exit code
≠ 0 if any check fails.

The build is separate from DirectAuth's: `run.js` builds the DirectAuth jar (`gradlew build`), then
this project (`gradlew -p tools/mod-tests`). Server and client are production ones and load the mod
jars as published (SRG names). The harness is compiled with Mojang names against remapped copies of
the mods (`remapMods` writes them, with their jar-in-jar libraries, to `build/mod-cache-named/`, only
for `compileOnly`) and reobfuscated (`reobfJar`); the same jar runs the scenario on the client and
adds the `/mt` commands on the server. The Forge server is installed once in `runs/mod-tests/_forge/`
and shared by the scenarios (their `libraries` folder is a link to it). The client is installed once
in `runs/mod-tests/_forge-client/` like a launcher would: Forge's installer (`--installClient`), plus
Minecraft's libraries and assets from Mojang; `run.js` builds the launch command from the version
files.

## Scenarios

Each scenario plays one account, `ModTester`, through several sessions with the same client:

1. **First join** (`registerNewChecking`): the mod's screen must not cover the chat while the player
   is jailed, and must open after `/register`.
2. **Impostor**: after a `/logout`, a new connection that never logs in sends the packet the mod's
   UI would send (choose an origin, release a Pokémon, bite...). It must have no effect.
3. **Owner**: a new connection logs in. The account must be as the owner left it, the screen must
   open again, and the same packet must now work (positive control).

| Scenario | Mod | What it checks |
|---|---|---|
| `baseline` | none | The harness itself: register, `/logout`, jailed rejoin, `/login`, RCON |
| `origins` | Origins (Forge) | Origin picker, choosing an origin |
| `mca` | MCA Reborn | Destiny screen, saving the character |
| `pixelmon` | Pixelmon | Starter screen (resent every 5 s), choosing a starter |
| `cobblemon` | Cobblemon | Choosing a starter and releasing a Pokémon from the jail |
| `vampirism` | Vampirism | A vampire biting an entity |
| `werewolves` | Werewolves | A werewolf biting an entity (direct damage) |
| `epic-fight` | Epic Fight | Per-tick packets and the animation registry check do not drop the connection; mode change |
| `voicechat` | Simple Voice Chat | No voice chat while jailed; it connects after `/login` without reconnecting |
| `aether` | The Aether | `spawn_in_aether`: a new player ends up in the Aether |
| `skyblock-builder` | Skyblock Builder | A new player ends up on the spawn island |
| `welcome-screen` | Welcome Screen | Client-only screen: it can be closed with Esc to register |
| `starter-kit` | Starter Kit | Kit choice screen (needs a timed client handshake), choosing a kit |
| `female-gender` | Female Gender | Appearance sync |

## Limitations

Pixelmon 9.2.3 crashes the server when a player leaves without picking a starter and is not back
within 5 s (it keeps resending them the starter screen). The impostor step does exactly that, so
the harness removes a leaving player from Pixelmon's list on the server
(`PixelmonChecks.workAroundStarterListLeak`). Its wild spawns are turned off in the scenario: some
of its entities fail to encode their data and drop the connection of a player nearby.

Tensura: Reincarnated and Dragon Survival, tested on `main`, have no Forge 1.20.1 build to test here,
and neither has Infiniverse.

## Files

| File | What it does |
|---|---|
| `mods.json` | Mods (pinned version, URL, sha512, side, required mods) and scenarios: `mods`, `serverMemory`/`clientMemory`, `serverProperties`, `serverFiles` / `clientFiles` (written before the first start; Welcome Screen's FancyMenu screen, which a modpack ships), `firstBoot` (edits after a first start that generates the mods' configs: `replace`, `copy`, `delete`, `write`), `serverSetup` (RCON commands before the client joins), `clientJvmArgs`, `timeoutMinutes` (15 by default). |
| `run.js` | Downloads the mods, installs Forge, builds, prepares the server and client directories, runs each scenario and prints the checks. |
| `src/main/java/.../ClientDriver.java` | Drives the client: connect, disconnect, commands, chat, screens, mod packets, RCON, results. |
| `src/main/java/.../ServerCommands.java`, `checks/` | `/mt <check> <player>`: mod state read on the server (party size, race, altar...). Each mod's checks load only when the mod is installed. |
| `src/main/java/.../scenarios/` | One class per scenario (`skyblock-builder` → `SkyblockBuilder`). |

## Adding a mod

1. Add it to `mods.json` with its exact file, URL and sha512 (Modrinth's API gives all three), its
   side and its required mods, plus a scenario.
2. Read what the mod does at first join and which packets its UI sends (decompiled sources go in
   `build/references/mods-1.20.1/`; `build/references/mods/` holds `main`'s NeoForge 1.21.1 ones).
3. Write the scenario class. Add a `/mt` check in `checks/` if the state is not visible over plain
   RCON, and register it in `ServerCommands` behind `ModList.isLoaded`.
4. Run it against the current DirectAuth and make sure every check fails or passes for the right
   reason. A positive control must not pass only because an earlier, jailed attempt leaked.
