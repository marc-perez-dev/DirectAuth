![DirectAuth Logo](src/main/resources/logo.png)

# DirectAuth

![Build Status](https://github.com/marc-perez-dev/DirectAuth/actions/workflows/build.yml/badge.svg)

DirectAuth is a **server-side** authentication mod for Minecraft NeoForge 1.21.1. It provides a secure login system for offline-mode servers, with optional auto-login for players who own a legitimate Minecraft account.

It is strictly server-side: clients do **not** need to install this mod to join. Players can connect with any vanilla client.

All database operations and password hashing run asynchronously, so the main server thread never freezes during logins.

## Key Features

* **Server-Side Only**: Install it on your server and players join with any vanilla client. Nothing to install client-side.
* **Zero-Configuration Database**: Uses an embedded SQLite database. No MySQL or external database server required.
* **No Lag**: All database I/O and password hashing run on separate threads, never on the server tick thread.
* **Strong Security**: Passwords are hashed with PBKDF2WithHmacSHA256 using a unique salt per user.
* **Online Auto-Login**: Players with a legitimate Mojang/Microsoft account can use `/online` to verify their account and skip typing passwords in future sessions.
* **Session Grace Period**: If a player disconnects and reconnects within the configured grace period, they stay authenticated without logging in again.
* **Anti-Bot Protection**: Configurable registration delay and a maximum number of accounts per IP address.
* **Strict Restrictions**: Unauthenticated players cannot move, chat, interact with blocks/entities, drop, move or pick up items (inventory and container clicks are blocked), attack, gain XP, or regenerate health. If they respawn, they appear at world spawn instead of the account's bed or respawn anchor. Other mods' automation (`FakePlayer`s such as deployers or miners) is never restricted.
* **Smart Data Migration**: When a player switches from offline to online mode their UUID changes, so the mod automatically migrates their data to the new UUID (see below).
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
| **/online** | `/online <password>` | Verifies your account against Mojang's servers to enable auto-login. **Migrates your data to a new UUID** — see the warning below. |
| **/directauth confirm** | `/directauth confirm` | Confirms a pending action when the mod requests it. |

### Admin commands (require OP level 4)

| Command | Usage | Description |
| :--- | :--- | :--- |
| **/directauth online** | `/directauth online <user> <true\|false>` | Toggles a player's online-mode status, migrating their data between the offline and online UUIDs. Enabling requires the player to be online and logged in; disabling also works while they are offline for accounts converted with this version. Connected players are kicked to reconnect with the new UUID. |
| **/directauth resetpass** | `/directauth resetpass <user> <newPassword>` | Resets a player's password, invalidates their saved sessions, lifts any failed-login lockout and kicks every connection of that account. |
| **/directauth unregister** | `/directauth unregister <user>` | Force-deletes a player's account (and kicks them if online). |
| **/directauth reload** | `/directauth reload` | Reloads the config and language files from disk at runtime — no server restart needed. |
| **/directauth resetlang** | `/directauth resetlang` | Deletes the language files so they regenerate from the mod's built-in defaults (discards manual edits). |

## ⚠️ Important: Online Mode Migration

When a player runs `/online`, their UUID changes from the offline UUID to their real Mojang UUID, and DirectAuth migrates their data to the new UUID. If any step of the migration fails, everything already moved is rolled back and the account stays in offline mode.

**By default** the mod already migrates vanilla data (inventory, ender chest, advancements, statistics) **and** a few common mods (graves/deaths, FTB Quests, SkinRestorer).

If your server uses **other** mods that store per-player data (e.g. Curios, Astral Sorcery, FTB Teams), the server administrator must add those folder names to the migration config **before** players run `/online`. Otherwise that mod-specific progress may be lost.

> **Always back up your world before migrating on a heavily modded server.**

## Installation

1. Download the `.jar` file.
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
* **Data Migration**: `migrationMap` — a map of *folder name → migration mode*. To support an extra mod, add its data folder there. Available modes:
    * `RENAME` — rename a single file that is named after the UUID (most vanilla data, SkinRestorer).
    * `DIRECTORY` — move/rename a whole folder named after the UUID (e.g. graves).
    * `TEXT_REPLACE` — rename the file **and** replace UUID strings inside its contents (e.g. FTB Quests).

  Example:

  ```json
  "migrationMap": {
    "playerdata": "RENAME",
    "stats": "RENAME",
    "advancements": "RENAME",
    "deaths": "DIRECTORY",
    "ftbquests": "TEXT_REPLACE",
    "skinrestorer": "RENAME"
  }
  ```

Message strings can be edited in the `DirectAuth-lang-*.json` files.

If the config file contains invalid JSON, DirectAuth logs an error and keeps running with the previous settings (or the defaults on startup) without overwriting your file; `/directauth reload` reports the failure.

## Troubleshooting (FAQ)

**Q: I keep getting teleported back when I move / I can't eat or regenerate health.**
A: You aren't authenticated yet. Use `/register <password>` (first time) or `/login <password>`.

**Q: I lost my items from [some mod] after using `/online`.**
A: The server administrator likely hadn't added that mod's data folder to `migrationMap`. Contact your admin.

## Technical Details

* **Hashing**: PBKDF2WithHmacSHA256 (100,000 iterations) with a unique salt per user, stored as `salt:hash`. The 10,000-iteration hashes from 1.0.0 are still accepted and upgraded on the next login.
* **Storage**: SQLite at `world/serverconfig/directauth.db` — no external database server. A legacy `DirectAuth_users.json` is migrated automatically on first run if present.
* **Upgrades**: When a new version changes the database, DirectAuth first saves a copy of it as `world/serverconfig/directauth-backup-<date>.db`; no manual backup is needed. Passwords stay in the format older versions read, so going back to 1.1 or 1.2 only takes swapping the jar.
* **Session Management**: Sessions are validated against the internal database (and Mojang's session servers for online users).
* **Protection**: The login listener is injected at high priority to prevent unauthorized packet processing.

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
