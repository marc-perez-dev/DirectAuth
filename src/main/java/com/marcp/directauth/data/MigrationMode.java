package com.marcp.directauth.data;

public enum MigrationMode {
    RENAME,         // Solo renombra el archivo (Vanilla, SkinRestorer)
    DIRECTORY,      // Renombra una carpeta entera (Deaths/Graves)
    TEXT_REPLACE    // Renombra archivo Y reemplaza UUIDs dentro (FTB Quests)
}
