# DirectAuth tests with real mods

End-to-end tests against real third-party mods. `run.js` starts a **production Fabric 1.21.1
server** (Fabric Loader + Fabric API, the versions of `gradle.properties`) with DirectAuth and the
mods of one scenario, then a **production Fabric client** with the same mods, headless under Xvfb.
The client runs a scripted scenario (`src/main/java/.../scenarios/`) through a small harness mod,
`directauth_modtests`, and checks the result over RCON.

The smoke test (`tools/smoke-test/`) covers DirectAuth itself with Mineflayer bots. Mineflayer is a
vanilla client, though, and cannot join a server with these mods. This suite checks how the jail
interacts with them: screens a mod opens at first join, packets a jailed impostor could send to a
mod, and first-join teleports.

## Requirements

- Linux with `xvfb-run` (Fedora: `xorg-x11-server-Xvfb`) and Mesa (software OpenGL is enough).
- Node.js 18 or newer (no npm dependencies) and Java 21 (`java` on the `PATH`, or `JAVA21=/path/to/java`).
- About 5 GB of free RAM: the server and the client run at the same time. Cobblemon gets 3 GB each
  (measured: 1.8 GB server, 2.8 GB client); the other scenarios 2 GB each (they use 0.7–1.7 GB).
- Internet access the first time: the mods (about 300 MB) are downloaded into `build/mod-cache/`
  and checked against the sha512 in `mods.json`; Fabric API into `build/fabric-cache/` (sha1 from
  Fabric's maven); the server and the client (about 1 GB with the assets) into
  `runs/mod-tests-fabric/_server` and `_client`.
- The Minecraft EULA accepted, either for the Fabric smoke test (`runs/smoke-test-fabric/eula.txt`)
  or here: `mkdir -p runs/mod-tests-fabric && echo eula=true > runs/mod-tests-fabric/eula.txt` (from
  the repo root).
- Ports 25570 (game) and 25580 (RCON) free on `127.0.0.1`.

## Usage

```bash
cd tools/mod-tests
node run.js                  # every scenario in mods.json
node run.js cobblemon mca    # some of them
```

To test a given DirectAuth jar (a release, or the code before a change) instead of building this
checkout: `DIRECTAUTH_JAR=path/to/directauth.jar node run.js ...`.

Each scenario starts from a fresh world in `runs/mod-tests-fabric/<scenario>/` and takes about a
minute (the whole suite about 10). The server and client logs stay there (`server.log`, `client.log`), next to
`results.jsonl`. Exit code ≠ 0 if any check fails.

Two variables change DirectAuth's config for a run (written to the world before the server starts):

- `LOG_JAIL_PAYLOADS=1` turns on `logJailPayloads`: `server.log` lists every payload a jailed player
  received (`payload <id> to unauthenticated ModTester`, with `(deferred ...)` for the deferred ones).
- `DIRECTAUTH_CONFIG='{"heldPayloadExceptions":[]}'` sets config fields. To check that a built-in id
  is needed, run its scenario without it: a check must fail. To check that the jail is what stops an
  impostor, list the mod's packet in `heldPayloadExceptions`: the impostor checks must fail.

## How it runs

- **Build**: `run.js` builds the DirectAuth release jar (`gradlew remapJar`), then this project
  (`gradlew -p tools/mod-tests remapJar`), a separate Loom build that compiles the harness against
  the downloaded mods (`modCompileOnly`: Loom remaps them from intermediary to Mojang names) and
  remaps it back to intermediary. Mods nested in them (jar-in-jar) are extracted to
  `build/mod-cache/nested/`; plain libraries nested by Loom (Kotlin...) to `nested-libs/`, which are
  not remapped. Minecraft, Fabric Loader, Fabric API, Loom and the mappings are DirectAuth's
  (`gradle.properties` of the repo root).
- **Server**: Fabric's server launcher (the same as the smoke test), run in the scenario's
  `server/` directory. The vanilla server, the libraries and Minecraft remapped to intermediary are
  shared through symlinks to `runs/mod-tests-fabric/_server`.
- **Client**: launched the way a launcher does it with Fabric's profile: the vanilla client jar,
  its libraries and assets from Mojang, Fabric Loader and its libraries from
  `meta.fabricmc.net/v2/versions/loader/<mc>/<loader>/profile/json`, main class `KnotClient`. That
  is the client players use (intermediary names, mods as published), so there are no dev-only
  problems such as synthetic lambda names. It runs under `xvfb-run` with the offline name
  `ModTester`.

## Scenarios

Each scenario plays one account, `ModTester`, through several sessions with the same client:

1. **First join** (`registerNewChecking`): the mod's screen must not cover the chat while the player
   is jailed, and must open after `/register`.
2. **Impostor**: after a `/logout`, a new connection that never logs in sends the packet the mod's
   UI would send (choose an origin, release a Pokémon...). It must have no effect.
3. **Owner**: a new connection logs in. The account must be as the owner left it, the screen must
   open again, and the same packet must now work (positive control).

| Scenario | Mod | What it checks |
|---|---|---|
| `baseline` | none | The harness itself: register, `/logout`, jailed rejoin, `/login`, RCON |
| `origins` | Origins (Apace) | Origin picker (`origins:s2c/open_origin_screen`), choosing an origin |
| `mca` | MCA Reborn | Destiny screen (`mca:open_destiny_gui_request`), saving the character |
| `tensura` | Tensura: Reincarnated | Race menu opened by the server, the vanilla container button that picks the race |
| `cobblemon` | Cobblemon | Choosing a starter and releasing a Pokémon from the jail |
| `voicechat` | Simple Voice Chat | No voice chat while jailed; it connects after `/login` without reconnecting |
| `aether` | The Aether | `spawn_in_aether`: a new player ends up in the Aether |
| `welcome-screen` | Welcome Screen | Client-only screen: it can be closed with Esc to register |
| `starter-kit` | Starter Kit | Kit choice screen (`starterkit:to_client_receive_kit_data_packet`) after a timed client handshake (`starterkit:to_server_announce_mod_is_installed_packet`, else Starter Kit falls back to chat commands), choosing a kit |
| `female-gender` | Female Gender | Appearance sync |

Not portable: the other mods tested on `main` have no Fabric 1.21.1 build on Modrinth (Pixelmon,
Vampirism, Werewolves, Epic Fight, Dragon Survival, Skyblock Builder, Infiniverse), and Neo Origins
is NeoForge-only (Origins replaces it).

## Limitations

- Loom cannot remap a mod built with a newer Loom than its own, so `mods.json` pins versions built
  with Loom 1.17 at most: Simple Voice Chat 2.6.24 (2.6.25 uses Loom 1.19, which needs Gradle to run
  on Java 25).
- The client renders with software OpenGL. `run.js` turns off the menu background blur: with it, a
  frame of MCA's Destiny screen sometimes took over 20 s and the harness gave up on the client (if
  the client thread stalls, the `scenario error` shows its stack).
- Origins has no stable release for 1.21.1: the scenario uses `1.13.0-pre.4`.
- Cobblemon: `starter chosen from the jail is not applied` is read 2 s after the impostor's packet,
  and Cobblemon can take longer to apply a starter; `still no starter after the impostor left`
  catches it either way.

## Files

| File | What it does |
|---|---|
| `mods.json` | Mods (pinned version, URL, sha512, side, required mods; Fabric API is added by `run.js`) and scenarios: `mods`, `serverMemory`/`clientMemory`, `serverProperties`, `serverFiles`/`clientFiles` (written before the first start), `firstBoot` (edits after a first start that generates the mods' configs: `replace`, `copy`, `delete`, `write`), `clientJvmArgs`. |
| `run.js` | Downloads the mods, builds, installs the server and client, prepares the game directories, runs each scenario and prints the checks. |
| `src/main/java/.../ClientDriver.java` | Drives the client: connect, disconnect, commands, chat, screens, mod packets, RCON, results. |
| `src/main/java/.../ServerCommands.java`, `checks/` | `/mt <check> <player>`: mod state read on the server (origin, party size, race...). Each mod's checks load only when the mod is installed. |
| `src/main/java/.../scenarios/` | One class per scenario (`female-gender` → `FemaleGender`). |

## Adding a mod

1. Add it to `mods.json` with its exact file, URL and sha512 (Modrinth's API gives all three), its
   side and the required mods it does not bundle, plus a scenario.
2. Read what the mod does at first join and which packets its UI sends (decompile the remapped jar
   from `tools/mod-tests/.gradle/loom-cache/remapped_mods/` into `build/references/mods/`), and run
   the scenario with `LOG_JAIL_PAYLOADS=1` to see what a jailed player receives.
3. Write the scenario class. Add a `/mt` check in `checks/` if the state is not visible over plain
   RCON, and register it in `ServerCommands` behind `loaded(<mod id>)`.
4. Run it against the current DirectAuth and make sure every check fails or passes for the right
   reason (see `DIRECTAUTH_CONFIG` above). A positive control must not pass only because an earlier,
   jailed attempt leaked.
