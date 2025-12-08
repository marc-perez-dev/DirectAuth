# DirectAuth (Authentication Mod) - Project Context

## Project Overview

This repository contains a Minecraft Mod built with **NeoForge** for version **1.21.1**, designed to handle player authentication (Offline/Premium modes). It also includes a documentation site powered by **Docusaurus**.

### Key Components

1.  **DirectAuth (Root Directory):**
    *   **Type:** NeoForge Minecraft Mod (Java 21).
    *   **Mod ID:** `directauth`.
    *   **Description:** Implements authentication commands (`/login`, `/register`, `/premium`), coordinate hiding for unauthenticated players, and restrictive access control.
    *   **Main Class:** `com.marcp.directauth.DirectAuth`.

2.  **Documentation (`Documentation/`):**
    *   **Type:** Docusaurus v3 Website (Node.js).

## Directory Structure

*   `src/main/java/com/marcp/directauth/`: Source code for the mod.
    *   `auth/`: Authentication logic (`LoginManager`, `MojangAPI`).
    *   `commands/`: Command implementations (`LoginCommand`, `RegisterCommand`, `PremiumCommand`).
    *   `config/`: Configuration handling (`ModConfig`).
    *   `data/`: Data persistence (`DatabaseManager`, `PositionManager`, `UserData`).
    *   `events/`: Event handlers (`ConnectionHandler`, `PlayerRestrictionHandler`).

## Configuration Files

*   `world/serverconfig/directauth-config.json`: General configuration and messages.
*   `world/serverconfig/directauth_users.json`: Registered user database.
*   `world/serverconfig/directauth_positions.json`: Temporary storage for hidden coordinates.

## Development & Usage


### 1. The Minecraft Mod (Root)

**Prerequisites:** Java 21 JDK.

**Build Commands:**

*   **Build Mod:** `gradlew build` (Output in `build/libs/`)
*   **Run Client:** `gradlew runClient`
*   **Run Server:** `gradlew runServer`
*   **Refresh Dependencies:** `gradlew --refresh-dependencies`

**Configuration:**
*   `gradle.properties`: Contains version definitions (`minecraft_version`, `mod_version`, etc.).
*   `src/main/resources/META-INF/neoforge.mods.toml`: Mod metadata (generated from templates).

### 2. Documentation Site (`Documentation/`)

**Prerequisites:** Node.js 18+.

**Commands (run inside `Documentation/`):**

*   **Install Dependencies:** `npm install`
*   **Start Dev Server:** `npm run start` (Live preview at `http://localhost:3000`)
*   **Build Static Site:** `npm run build`

## Coding Conventions

*   **Java/NeoForge:**
    *   Follows standard NeoForge event-driven architecture.
    *   Uses Dependency Injection or Singleton patterns for Managers (`LoginManager`, `DatabaseManager`).
    *   Target Java 21 features.
*   **Style:** Standard Java naming conventions (PascalCase for classes, camelCase for methods/variables).
