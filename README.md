# DirectAuth

DirectAuth is a server-side authentication mod for Minecraft NeoForge 1.21.1. It provides a secure, offline-mode authentication system with optional auto-login capabilities for online users.

This mod relies on a local SQLite database and handles all I/O operations asynchronously to ensure server performance remains unaffected during player logins.

## Key Features

* **Security**: User passwords are protected using PBKDF2WithHmacSHA256 hashing with unique salts per user.
* **Asynchronous Processing**: Database queries run on a separate thread pool to prevent main thread blocking (lag) during authentication checks.
* **Online Auto-Login**: Users with valid Mojang/Microsoft accounts can verify their status to bypass the login command in future sessions.
* **Data Migration**: When a user switches from offline mode to online mode, the mod automatically migrates their inventory, advancements, statistics, and position data to their new online UUID.
* **Strict Restrictions**: Unauthenticated players are restricted from moving, chatting, interacting with blocks, dropping items, or regenerating health.
* **Zero Configuration Database**: Uses an embedded SQLite database. No external MySQL server setup is required.

## Commands

| Command | Usage | Description |
| :--- | :--- | :--- |
| **/register** | `/register <password>` | Creates a new account. Required upon first connection. |
| **/login** | `/login <password>` | Authenticates the user session. |
| **/online** | `/online` | Verifies the account with Mojang servers and enables auto-login. **Warning:** This migrates player data to a new UUID. |
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

## Compatibility Note

DirectAuth uses a local SQLite database stored within the world directory.
* **Supported:** Single dedicated servers, local LAN worlds.
* **Not Supported:** BungeeCord/Velocity networks requiring data synchronization across multiple server instances (e.g., Lobby to Survival transfers).

## License

This project is licensed under the MIT License.