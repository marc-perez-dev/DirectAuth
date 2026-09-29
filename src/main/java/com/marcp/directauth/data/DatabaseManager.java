package com.marcp.directauth.data;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.*;
import java.io.IOException;
import java.nio.file.Files;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.util.Locale;
import java.util.Map;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Acceso a SQLite. TODAS las operaciones públicas son asíncronas y se ejecutan en un único
 * hilo (dbExecutor), de modo que quedan serializadas: una comprobación seguida de una escritura
 * dentro de la misma tarea es atómica respecto al resto de operaciones.
 */
public class DatabaseManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final String connectionString;
    private Connection connection;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DirectAuth-Database");
        thread.setDaemon(true);
        return thread;
    });

    /** Resultado de un intento de registro. */
    public enum RegistrationResult { OK, ALREADY_REGISTERED, IP_LIMIT_REACHED }

    @FunctionalInterface
    private interface SqlTask<T> {
        T run() throws SQLException;
    }

    public DatabaseManager(Path worldPath) {
        // Guardamos en world/serverconfig/directauth.db
        Path dbPath = worldPath.resolve("serverconfig").resolve("directauth.db");
        this.connectionString = "jdbc:sqlite:" + dbPath.toString();

        initDatabase();

        // MIGRACIÓN AUTOMÁTICA: Si existe el JSON viejo, lo importamos
        Path oldJsonPath = worldPath.resolve("serverconfig").resolve("DirectAuth_users.json");
        if (Files.exists(oldJsonPath)) {
            migrateFromJson(oldJsonPath);
        }
    }

    private void initDatabase() {
        try {
            // 1. Forzamos la carga del driver (Crucial en entornos moddeados)
            Class.forName("org.sqlite.JDBC");

            // 2. Establecemos conexión
            connection = DriverManager.getConnection(connectionString);

            try (Statement stmt = connection.createStatement()) {
                // Creamos la tabla si no existe
                stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                        "username TEXT PRIMARY KEY, " +
                        "passwordHash TEXT NOT NULL, " +
                        "isPremium INTEGER DEFAULT 0, " +
                        "onlineUUID TEXT, " +
                        "registrationIp TEXT, " + // Nueva columna
                        "offlineUUID TEXT" +
                        ");");

                // MIGRACIÓN PARA SERVIDORES ANTIGUOS
                // Intentamos añadir la columna 'registrationIp' a la tabla existente.
                // Si la columna ya existe, SQLite lanzará un error que ignoraremos de forma segura.
                try {
                    stmt.execute("ALTER TABLE users ADD COLUMN registrationIp TEXT;");
                    LOGGER.info("DirectAuth: Database updated (IP column added).");
                } catch (SQLException ignored) {
                    // La columna ya existe, no hacemos nada.
                }
                // UUID offline exacta de las cuentas premium (para poder migrar de vuelta)
                try {
                    stmt.execute("ALTER TABLE users ADD COLUMN offlineUUID TEXT;");
                    LOGGER.info("DirectAuth: Database updated (offlineUUID column added).");
                } catch (SQLException ignored) {
                    // La columna ya existe, no hacemos nada.
                }
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("CRITICAL: SQLite driver not found. Make sure the library is bundled with the mod.", e);
        } catch (SQLException e) {
            throw new RuntimeException("CRITICAL: Failed to connect to the SQLite database.", e);
        }
    }

    /** Espera a que terminen las escrituras encoladas antes de cerrar la conexión. */
    public void close() {
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.warn("DirectAuth: Pending database operations did not finish in time.");
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            LOGGER.error("DirectAuth: Error closing the database", e);
        }
    }

    /** Clave de la cuenta: nombre en minúsculas independiente del locale del sistema. */
    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    /**
     * Versiones antiguas guardaban el nombre con toLowerCase() del locale del sistema (en un
     * servidor turco 'I' pasa a 'ı'). Las consultas buscan ambas variantes con
     * "username IN (?, ?)" para seguir encontrando esas filas.
     */
    private static final String KEY_MATCH = "username IN (?, ?)";

    private static int bindKey(PreparedStatement pstmt, int index, String username) throws SQLException {
        pstmt.setString(index, key(username));
        pstmt.setString(index + 1, username.toLowerCase());
        return index + 2;
    }

    private <T> CompletableFuture<T> submit(SqlTask<T> task) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.run();
                } catch (SQLException e) {
                    throw new CompletionException(e);
                }
            }, dbExecutor);
        } catch (RejectedExecutionException e) {
            // La base de datos ya se cerró (servidor apagándose)
            return CompletableFuture.failedFuture(e);
        }
    }

    // --- MÉTODOS CRUD (asíncronos) ---

    public CompletableFuture<UserData> getUserAsync(String username) {
        return submit(() -> getUser(username));
    }

    /**
     * Cambia solo el Modo Online (sin tocar la contraseña, para no pisar un cambio concurrente).
     * @return true si la cuenta existía y se actualizó.
     */
    public CompletableFuture<Boolean> setOnlineModeAsync(String username, boolean premium, String onlineUUID, String offlineUUID) {
        return submit(() -> {
            String sql = "UPDATE users SET isPremium = ?, onlineUUID = ?, offlineUUID = ? WHERE " + KEY_MATCH;
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, premium ? 1 : 0);
                pstmt.setString(2, onlineUUID);
                pstmt.setString(3, offlineUUID);
                bindKey(pstmt, 4, username);
                return pstmt.executeUpdate() > 0;
            }
        });
    }

    /** Cambia solo la contraseña. @return true si la cuenta existía. */
    public CompletableFuture<Boolean> updatePasswordAsync(String username, String passwordHash) {
        return submit(() -> {
            String sql = "UPDATE users SET passwordHash = ? WHERE " + KEY_MATCH;
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, passwordHash);
                bindKey(pstmt, 2, username);
                return pstmt.executeUpdate() > 0;
            }
        });
    }

    /**
     * Sustituye el hash solo si no ha cambiado mientras tanto (p. ej. al regenerar un hash
     * antiguo tras un login, sin pisar un /directauth resetpass concurrente).
     */
    public CompletableFuture<Boolean> replacePasswordHashAsync(String username, String expectedHash, String newHash) {
        return submit(() -> {
            String sql = "UPDATE users SET passwordHash = ? WHERE " + KEY_MATCH + " AND passwordHash = ?";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, newHash);
                bindKey(pstmt, 2, username);
                pstmt.setString(4, expectedHash);
                return pstmt.executeUpdate() > 0;
            }
        });
    }

    /** @return true si la cuenta existía y se borró. */
    public CompletableFuture<Boolean> deleteUserAsync(String username) {
        return submit(() -> {
            String sql = "DELETE FROM users WHERE " + KEY_MATCH;
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                bindKey(pstmt, 1, username);
                return pstmt.executeUpdate() > 0;
            }
        });
    }

    /** Comprobación previa (sin escribir) para no gastar un hash en registros que se van a rechazar. */
    public CompletableFuture<RegistrationResult> checkRegistrationAsync(String username, String ip, int maxAccountsPerIP) {
        return submit(() -> checkRegistration(username, ip, maxAccountsPerIP));
    }

    /**
     * Registra la cuenta si sigue cumpliendo las condiciones. La comprobación y el INSERT se
     * ejecutan en la misma tarea del hilo de la DB, así que no hay carrera entre registros.
     */
    public CompletableFuture<RegistrationResult> registerUserAsync(String username, String passwordHash, String ip, int maxAccountsPerIP) {
        return submit(() -> {
            RegistrationResult check = checkRegistration(username, ip, maxAccountsPerIP);
            if (check != RegistrationResult.OK) return check;

            String sql = "INSERT INTO users(username, passwordHash, isPremium, onlineUUID, registrationIp) VALUES(?,?,0,NULL,?)";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, key(username));
                pstmt.setString(2, passwordHash);
                pstmt.setString(3, ip);
                pstmt.executeUpdate();
            }
            return RegistrationResult.OK;
        });
    }

    // --- Implementación síncrona (solo desde dbExecutor) ---

    private RegistrationResult checkRegistration(String username, String ip, int maxAccountsPerIP) throws SQLException {
        if (userExists(username)) return RegistrationResult.ALREADY_REGISTERED;
        if (countAccountsByIP(ip) >= maxAccountsPerIP) return RegistrationResult.IP_LIMIT_REACHED;
        return RegistrationResult.OK;
    }

    private int countAccountsByIP(String ip) throws SQLException {
        if (ip == null) return 0;
        String sql = "SELECT COUNT(*) FROM users WHERE registrationIp = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, ip);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private UserData getUser(String username) throws SQLException {
        String sql = "SELECT * FROM users WHERE " + KEY_MATCH;
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            bindKey(pstmt, 1, username);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    UserData data = new UserData(rs.getString("username"), rs.getString("passwordHash"));
                    data.setPremium(rs.getInt("isPremium") == 1);
                    data.setOnlineUUID(rs.getString("onlineUUID"));
                    data.setOfflineUUID(rs.getString("offlineUUID"));
                    return data;
                }
            }
        }
        return null;
    }

    private boolean userExists(String username) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE " + KEY_MATCH;
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            bindKey(pstmt, 1, username);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    // --- MIGRACIÓN (Solo se ejecuta una vez) ---
    private void migrateFromJson(Path jsonPath) {
        LOGGER.info("DirectAuth: Migrating JSON database to SQLite...");
        Map<String, UserData> legacyUsers;
        try {
            String jsonContent = Files.readString(jsonPath);
            legacyUsers = new Gson().fromJson(jsonContent, new TypeToken<Map<String, UserData>>(){}.getType());
        } catch (IOException | JsonParseException e) {
            LOGGER.error("DirectAuth: Could not read {} ({}). The file was left in place; fix it and restart to retry the import.", jsonPath, e.getMessage());
            return;
        }

        if (legacyUsers != null) {
            String sql = "INSERT OR IGNORE INTO users(username, passwordHash, isPremium, onlineUUID) VALUES(?,?,?,?)";
            try {
                // Usamos una transacción para que sea rápido (y todo-o-nada)
                connection.setAutoCommit(false);
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    for (Map.Entry<String, UserData> entry : legacyUsers.entrySet()) {
                        UserData user = entry.getValue();
                        // La clave del mapa era el nombre en minúsculas; la usamos si falta el campo
                        String username = user != null && user.getUsername() != null ? user.getUsername() : entry.getKey();
                        if (user == null || username == null || user.getPasswordHash() == null) {
                            LOGGER.warn("DirectAuth: Skipping malformed legacy user entry '{}'", entry.getKey());
                            continue;
                        }
                        pstmt.setString(1, key(username));
                        pstmt.setString(2, user.getPasswordHash());
                        pstmt.setInt(3, user.isPremium() ? 1 : 0);
                        pstmt.setString(4, user.getOnlineUUID());
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    LOGGER.error("DirectAuth: JSON import failed and was rolled back. {} was left in place; restart to retry.", jsonPath, e);
                    return; // No renombramos: la importación se reintentará en el siguiente arranque
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                LOGGER.error("DirectAuth: JSON import failed. {} was left in place; restart to retry.", jsonPath, e);
                return;
            }
        }

        try {
            // Renombrar el JSON para no volver a importarlo (solo tras un commit correcto)
            Files.move(jsonPath, jsonPath.resolveSibling("DirectAuth_users.json.MIGRATED"));
            LOGGER.info("DirectAuth: Migration completed.");
        } catch (IOException e) {
            LOGGER.error("DirectAuth: Imported users, but could not rename {}: {}", jsonPath, e.getMessage());
        }
    }
}
