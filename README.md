![DirectAuth Logo](src/main/resources/logo.png)

# DirectAuth

![Build Status](https://github.com/marc-perez-dev/DirectAuth/actions/workflows/build.yml/badge.svg)

DirectAuth is a server-side authentication mod for Minecraft NeoForge 1.21.1. It provides a secure, offline-mode authentication system with optional auto-login capabilities for online users.

This mod relies on a local SQLite database and handles all I/O operations asynchronously to ensure server performance remains unaffected during player logins.

## Key Features

* **Security**: User passwords are protected using PBKDF2WithHmacSHA256 hashing with unique salts per user.
* **Asynchronous Processing**: Database queries run on a separate thread pool to prevent main thread blocking (lag) during authentication checks.
* **Online Auto-Login**: Users with valid Mojang/Microsoft accounts can verify their status to bypass the login command in future sessions.
* **Smart Data Migration (BETA)**: When a user switches from offline to online mode, the mod automatically migrates their data to the new Mojang UUID.
    * *Supported by default:* Inventory, Ender Chest, Advancements, Statistics, Potions/Health.
    * *Mod Support:* Server admins can configure `foldersToMigrate` in the config file to support data from other mods (e.g., `curios`, `astralsorcery`).
    * *Safety:* Always backup your world before performing migrations on heavily modded servers.
* **Strict Restrictions**: Unauthenticated players are restricted from moving, chatting, interacting with blocks, dropping items, or regenerating health.
* **Zero Configuration Database**: Uses an embedded SQLite database. No external MySQL server setup is required.

### ⚠️ IMPORTANT WARNING: ONLINE MODE MIGRATION

**Before using `/online`:**
Al activar el modo online, tu UUID cambiará y tus datos serán migrados. Por defecto, DirectAuth solo migra datos Vanilla (Inventario, Estadísticas, Logros).

**If you use other mods** that save their own data (e.g., Astral Sorcery, FTB Chunks), the server administrator **MUST** add those folder names to the `foldersToMigrate` list in `directauth-config.json` **BEFORE** players run this command. Failure to do so may result in the loss of mod-specific progress. **It is highly recommended to backup the world before migrating.**

## Commands

| Command | Usage | Description |
| :--- | :--- | :--- |
| **/register** | `/register <password>` | Creates a new account. Required upon first connection. |
| **/login** | `/login <password>` | Authenticates the user session. |
| **/online** | `/online` | **(BETA)** Verifies the account with Mojang servers and enables auto-login. **Warning:** This migrates player data to a new UUID. |
| **/directauth** | `/directauth online <user> <true/false>` | Admin command to manually toggle a player's online-mode status. |

## Installation

1.  Download the `.jar` file.
2.  Place it in the `mods` folder of your NeoForge 1.21.1 server.
3.  Restart the server.

The configuration file will be generated at `world/serverconfig/directauth-config.json`.
The database file will be created at `world/serverconfig/directauth.db`.

## Configuration

You can modify `world/serverconfig/directauth-config.json` to adjust:
* Authentication timeout limits (kick timer).
* Password length requirements (min/max).
* Login cooldowns and max attempts.
* Specific restrictions (chat, movement, block interaction).
* Translation strings (default messages).
* **Data Migration List**: You can add custom folder names (e.g., `curios`, `ftbteams`) to `foldersToMigrate` to ensure mod-specific data is transferred when a player switches to Online Mode.

## Troubleshooting (FAQ)

**Q: I keep getting teleported back when I move / I can't eat or regenerate health.**
A: You are not authenticated yet. Please use `/register <password>` (first time) or `/login <password>`.

**Q: I lost my items from [Mod Name] after using `/online`.**
A: The server administrator likely hasn't configured the `foldersToMigrate` list to include that mod's data folder. Please contact your admin.

## Compatibility Note

DirectAuth uses a local SQLite database stored within the world directory.
* **Supported:** Single dedicated servers, local LAN worlds.
* **Not Supported:** BungeeCord/Velocity networks requiring data synchronization across multiple server instances (e.g., Lobby to Survival transfers).

## License

This project is licensed under the MIT License.