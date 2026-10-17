# DirectAuth smoke tests

End-to-end tests against a real server. `run-all.sh` builds the mod from this checkout, starts the
NeoForge dev server (offline mode, flat world) in `runs/smoke-test/`, and drives it with
[Mineflayer](https://github.com/PrismarineJS/mineflayer) bots, RCON and direct checks on the
SQLite database and the world files. Bots connect from different loopback addresses
(`127.0.0.x`) to simulate players on different IPs.

## Requirements

- Linux (uses the `127.0.0.0/8` loopback range, `mkfifo` and `setsid`).
- Node.js 22.5 or newer (`node:sqlite`).
- Ports 25565 and 25575 free on `127.0.0.1`.
- Internet access: the premium tests query the Mojang API and session server with the real
  account name `Notch` (lookup only, nobody logs into that account).
- The Minecraft EULA accepted for the test server. Read https://aka.ms/MinecraftEULA, then:
  `mkdir -p runs/smoke-test && echo eula=true > runs/smoke-test/eula.txt`

## Usage

```bash
cd tools/smoke-test
npm ci
./run-all.sh          # wipes runs/smoke-test/world; exit code != 0 if anything fails
```

A full run takes about 4 minutes. Before a release, also check that going back to the previous
version still works (players keep logging in on a database written by this version):

```bash
DOWNGRADE_REF=main ./run-all.sh   # any git ref: a release tag, a commit, a branch
```

It builds that ref in a temporary `git worktree`, runs it against the same world, logs in with
accounts written by this version, then starts this version again.

To work on a single test against the current world:

```bash
./up.sh               # start the server (keeps the world)
node tests.js 'lockout'   # regex on test names
./down.sh
```

Tests assume a fresh world. When re-running a test against a world that already has its
accounts, some tests take fresh names from the environment: `JAIL`, `JAIL_IP`, `DEAD`, `DEAD_IP`,
`DEAD_IP2`, `PICK`.

## Files

| File | What it does |
|---|---|
| `tests.js` | Tests against a running server (registration, login, lockout, jail, respawn, sessions, admin commands, premium migration, legacy hashes, config, RCON, races). |
| `restart.js` | Phases that need restarts: shutdown with a `/login` in flight, positions surviving a restart, automatic database backup when upgrading an older schema, corrupt `positions.json`, legacy `DirectAuth_users.json` import, malformed config at startup, downgrade and re-upgrade, Turkish locale. |
| `lib.js` | Helpers: bots, message expectations, RCON, SQLite, player state. |
| `server.sh` | Starts the server like `runServer` but in `runs/smoke-test/`, without GUI, with the console read from the `console` FIFO. |
| `common.sh`, `run-all.sh`, `restart-run.sh`, `up.sh`, `down.sh` | Orchestration. |

`server.out` (current server log), `server-all.out` (all restarts of the last run) and
`server-prev.out` are written next to the scripts and ignored by git.

## Expected errors in the server log

Some tests provoke errors on purpose; `run-all.sh` prints them at the end:

- `Failed migrating ... rolling back` and `AccessDeniedException` on `stats/`: the rollback test
  makes `stats/` read-only.
- `Could not read ... DirectAuth-config.json`, `Unknown migration mode`, `Could not read saved
  positions`, `Skipping malformed legacy user entry`: the corrupt-file tests.
- With `DOWNGRADE_REF`, whatever the previous version logs about the newer database (it is
  started on purpose against data written by this version).

## Not covered

- Premium auto-login with a real paid account (needs a real Mojang session).
- Singleplayer / integrated server.
- Mojang API unreachable (`errMojangUnavailable`).
- Other mods (`FakePlayer` machines, real FTB Quests data; FTB files are simulated).

Mineflayer is a protocol client, not the vanilla client: for example it does not roll back a
predicted block break when the server acknowledges the sequence, so tests do not rely on the
bot's view of a block it failed to break.
