package com.marcp.directauth.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

public class MojangAPI {
    private static final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String API_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    /**
     * Obtiene el UUID online de un jugador de forma asíncrona (sin bloquear ningún hilo).
     * @param username Nombre del jugador
     * @return CompletableFuture con el UUID (sin guiones), o null si la cuenta no existe.
     *         Se completa con excepción si no se pudo consultar a Mojang (red, timeout, error HTTP).
     */
    public static CompletableFuture<String> getOnlineUUID(String username) {
        // Vanilla acepta en offline cualquier ASCII imprimible ('#', '?', '/', '[', ...), pero una
        // cuenta de Mojang solo puede tener [A-Za-z0-9_]{3,16}. Otros nombres no se envían nunca:
        // p. ej. "Notch#" se convertiría en la petición de "Notch" y devolvería su cuenta.
        if (username == null || !VALID_NAME.matcher(username).matches()) {
            return CompletableFuture.completedFuture(null);
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL + username))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                // Defensa extra: el perfil devuelto debe ser exactamente el nombre consultado
                if (!json.has("name") || !username.equalsIgnoreCase(json.get("name").getAsString())) {
                    return null;
                }
                return json.get("id").getAsString(); // UUID sin guiones
            } else if (response.statusCode() == 204 || response.statusCode() == 404) {
                return null; // Usuario no existe
            }
            throw new CompletionException(new IOException("Mojang API returned HTTP " + response.statusCode()));
        });
    }
    
    /**
     * Formatea UUID sin guiones a formato con guiones
     */
    public static String formatUUID(String uuidWithoutDashes) {
        if (uuidWithoutDashes == null || uuidWithoutDashes.length() != 32) {
            return null;
        }
        return uuidWithoutDashes.substring(0, 8) + "-" +
               uuidWithoutDashes.substring(8, 12) + "-" +
               uuidWithoutDashes.substring(12, 16) + "-" +
               uuidWithoutDashes.substring(16, 20) + "-" +
               uuidWithoutDashes.substring(20);
    }
}
