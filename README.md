![DirectAuth Logo](src/main/resources/logo.png)

# DirectAuth

![Build Status](https://github.com/marc-perez-dev/DirectAuth/actions/workflows/build.yml/badge.svg)

DirectAuth is a **server-side** authentication mod for Minecraft NeoForge 1.21.1, also available for Forge 1.20.1 (on the [`forge-1.20.1`](https://github.com/marc-perez-dev/DirectAuth/tree/forge-1.20.1) branch). It provides a secure login system for offline-mode servers, with optional auto-login for players who own a legitimate Minecraft account.

It is strictly server-side: clients do **not** need to install this mod to join. Players can connect with any vanilla client.

All database operations and password hashing run asynchronously, so the main server thread never freezes during logins.

## Key Features

* **Server-Side Only**: Install it on your server and players join with any vanilla client. Nothing to install client-side.
* **Zero-Configuration Database**: Uses an embedded SQLite database. No MySQL or external database server required.
* **No Lag**: All database I/O and password hashing run on separate threads, never on the server tick thread.
* **Strong Security**: Passwords are hashed with PBKDF2WithHmacSHA256 using a unique salt per user.
* **Online Auto-Login**: Players with a legitimate Mojang/Microsoft account can use `/online` to verify their account and skip typing passwords in future sessions. They keep their inventory, progress and every mod's data, and get their real skin.
* **Session Grace Period**: If a player disconnects and reconnects within the configured grace period, they stay authenticated without logging in again.
* **Anti-Bot Protection**: Configurable registration delay and a maximum number of accounts per IP address.
* **Strict Restrictions**: Unauthenticated players cannot move, chat, interact with blocks/entities, drop, move or pick up items (inventory and container clicks are blocked), attack, gain XP, or regenerate health. If they respawn, they appear at world spawn instead of the account's bed or respawn anchor. Other mods' automation (`FakePlayer`s such as deployers or miners) is never restricted.
* **Safe with Other Mods**: Until a player logs in, whatever their client sends to other mods waits and only runs once they authenticate (so someone using another player's name cannot release their Pokémon or pick their origin), op-only packets such as command block edits are ignored, and screens other mods open at first join (origin, Destiny, starter, race selection...) show up after `/register` or `/login` instead of covering the chat.
* **Localization**: Ships with English (`en`) and Spanish (`es`); fully customizable message strings.

## Commands

### Player commands

| Command | Usage | Description |
| :--- | :--- | :--- |
| **/register** | `/register <password>` | Creates a new account. Required on first connection. |
| **/login** | `/login <password>` | Authenticates your session. |
| **/logout** | `/logout` | Logs you out (disconnects you from the server). |
| **/changepassword** | `/changepassword <oldPassword> <newPassword>` | Changes your password. Requires being logged in. Your Online Mode status is kept; other sessions of the account are logged out. |
| **/unregister** | `/unregister <password>` | Permanently deletes your own account (confirms with your password). |
| **/online** | `/online <password>` | Verifies your account against Mojang's servers to enable auto-login from your next join. Your player data stays as it is (see [Online Mode](#online-mode)). |
| **/directauth confirm** | `/directauth confirm` | Confirms a pending action when the mod requests it. |

### Admin commands (require OP level 4)

| Command | Usage | Description |
| :--- | :--- | :--- |
| **/directauth online** | `/directauth online <user> <true\|false>` | Toggles a player's Online Mode. Enabling requires the player to be online and logged in the first time (their account keeps the UUID they play with, which depends on the exact case of their name); disabling always works. Nobody is kicked. |
| **/directauth resetpass** | `/directauth resetpass <user> <newPassword>` | Resets a player's password, invalidates their saved sessions, lifts any failed-login lockout and kicks every connection of that account. |
| **/directauth unregister** | `/directauth unregister <user>` | Force-deletes a player's account (and kicks them if online). |
| **/directauth reload** | `/directauth reload` | Reloads the config and language files from disk at runtime — no server restart needed. |
| **/directauth resetlang** | `/directauth resetlang` | Deletes the language files so they regenerate from the mod's built-in defaults (discards manual edits). |

## Online Mode

Online Mode only changes how a player logs in: automatically, through Mojang, instead of with a password. Their account keeps the UUID it already had, so their inventory, statistics, advancements and every mod's data (quests, teams, starter kits...) stay exactly as they were, and nothing on disk is moved. Players also get their real skin.

Accounts that enabled Online Mode with DirectAuth 1.3.0 or older had their data moved to their Mojang UUID back then. They keep playing with that UUID, also if an admin switches them back to offline mode.

## Installation

1. Download the NeoForge 1.21.1 `.jar` from [Modrinth](https://modrinth.com/mod/directauth) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/directauth).
2. Place it in the `mods` folder of your NeoForge 1.21.1 server.
3. Restart the server.

On first launch the following files are generated:

* Config: `world/serverconfig/DirectAuth-config.json`
* Language files: `world/serverconfig/DirectAuth-lang-en.json` and `DirectAuth-lang-es.json`
* Database: `world/serverconfig/directauth.db`

## Configuration

Edit `world/serverconfig/DirectAuth-config.json` to customize:

* **Language**: `language` (`"en"` or `"es"`).
* **Security**: `minPasswordLength`, `maxPasswordLength`, `maxLoginAttempts`, `loginCooldownMs`, `loginLockoutSeconds` (how long logins are blocked after `maxLoginAttempts` failed attempts; attempts are counted per account **and IP**, so reconnecting does not reset them but someone else cannot lock you out from another IP; `0` = just kick at the limit), `loginTimeout` (seconds before a non-authenticated player is kicked).
* **Sessions**: `sessionGracePeriod` (seconds a session survives after disconnect) and `sessionCleanupInterval` (minutes between cleanups of expired sessions).
* **Anti-Bot**: `registrationDelay` (seconds to wait before a fresh player can register) and `maxAccountsPerIP`.
* **Other mods**: see [Compatibility with other mods](#compatibility-with-other-mods).
    * `deferredScreenPayloads` — packets of other mods that open a screen at first join. An unauthenticated player gets them after logging in.
    * `heldPayloadExceptions` — packets an unauthenticated player's client may still send to other mods. Everything else waits until they log in.
    * `logJailPayloads` — `true` logs every packet of other mods that unauthenticated players receive, to find the one that opens a mod's screen.

Message strings can be edited in the `DirectAuth-lang-*.json` files.

If the config file contains invalid JSON, DirectAuth logs an error and keeps running with the previous settings (or the defaults on startup) without overwriting your file; `/directauth reload` reports the failure.

## Troubleshooting (FAQ)

**Q: I keep getting teleported back when I move / I can't eat or regenerate health.**
A: You aren't authenticated yet. Use `/register <password>` (first time) or `/login <password>`.

**Q: I lost items or mod progress after using `/online` on an older version.**
A: Up to 1.3.0, `/online` moved the player's data to their Mojang UUID while the server was running, and many mods overwrote it from memory. From 1.3.1 nothing is moved. What was lost then may still be on disk under the player's offline UUID; the server admin can check the world's `playerdata` folder and each mod's data.

## Technical Details

* **Hashing**: PBKDF2WithHmacSHA256 (100,000 iterations) with a unique salt per user, stored as `salt:hash`. The 10,000-iteration hashes from 1.0.0 are still accepted and upgraded on the next login.
* **Storage**: SQLite at `world/serverconfig/directauth.db` — no external database server. A legacy `DirectAuth_users.json` is migrated automatically on first run if present.
* **Upgrades**: When a new version changes the database, DirectAuth first saves a copy of it as `world/serverconfig/directauth-backup-<date>.db`; no manual backup is needed. Passwords stay in the format older versions read, so going back to 1.1 or 1.2 only takes swapping the jar. One exception: on older versions, players who enabled Online Mode with 1.3.1 or newer join with their Mojang UUID and find an empty inventory until you upgrade again. Their real data stays under their offline UUID, as long as you don't change their Online Mode on the older version (switching it off there moves the new, empty data over it and keeps the real data as `.bak` files).
* **Session Management**: Sessions are validated against the internal database (and Mojang's session servers for online users).
* **Protection**: The login listener is injected at high priority to prevent unauthorized packet processing.

## Compatibility with other mods

Until a player logs in, DirectAuth keeps them away from other mods:

* **What their client sends to other mods** waits and runs once they log in; if they disconnect first, it never runs. Only the last packet of each kind is kept. This keeps someone who joins with another player's name from acting for that account, and one-time requests still work after login (Simple Voice Chat connects as soon as the player logs in).
* **Op-only vanilla packets** (command blocks, structure blocks, difficulty, NBT queries, book editing, recipe placing, menu buttons) are ignored, even for an op's name.
* **Chat** is dropped as it arrives, so no mod reads it either (MCA's villager AI would otherwise answer it).
* **Screens other mods open when a player joins** are shown after `/register` or `/login`, so they do not cover the chat. Built in: Neo Origins, MCA Reborn (Destiny), Pixelmon, Dragon Survival and Starter Kit's kit choice. Menus the server opens (Tensura's race selection) also wait.
* **First-join teleports** of other mods are kept: a player who has never played stays where the game or the mod put them, whether the mod moves them before or after DirectAuth sees them join.

If another mod opens a screen at first join that hides the chat:

1. Set `logJailPayloads` to `true` and run `/directauth reload`.
2. Join with a new account. The server log lists the packets the player received before logging in, for example `payload examplemod:open_screen to unauthenticated Steve`.
3. Add that id to `deferredScreenPayloads`, set `logJailPayloads` back to `false` and reload.

Screens that only exist on the client (for example Welcome Screen) never reach the server. They usually close with Esc; if not, raise `loginTimeout`.

These mods are tested with a real client before each release: Neo Origins, MCA Reborn, Pixelmon, Tensura: Reincarnated, Dragon Survival, Cobblemon, Vampirism, Werewolves, Epic Fight, Simple Voice Chat, The Aether, Skyblock Builder, Welcome Screen, Starter Kit and Female Gender.

## Compatibility

* **Loader**: NeoForge 1.21.1
* **Side**: Server-side only — works with any vanilla client and can be included in any modpack.
* **Supported**: Dedicated servers and local LAN worlds.
* **Not supported**: BungeeCord/Velocity networks that need player data synchronized across multiple server instances (e.g. Lobby → Survival transfers), since data is stored in the local world directory.

## 💖 Support the Project

DirectAuth is open-source and free to use. If you appreciate the work that goes into building and maintaining it, please consider supporting me!

☕ [Sponsor me on GitHub](https://github.com/sponsors/marc-perez-dev)

**Source Code & Issues:** [GitHub Repository](https://github.com/marc-perez-dev/DirectAuth) · [Report Issues](https://github.com/marc-perez-dev/DirectAuth/issues)

## License

This project is licensed under the MIT License.
