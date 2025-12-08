package com.marcp.directauth.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

public class MojangAPI {
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final String API_URL = "https://api.mojang.com/users/profiles/minecraft/";
    
    /**
     * Obtiene el UUID online de un jugador de forma asíncrona
     * @param username Nombre del jugador
     * @return CompletableFuture con el UUID (sin guiones) o null si no existe
     */
    public static CompletableFuture<String> getOnlineUUID(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL + username))
                    .GET()
                    .build();
                
                HttpResponse<String> response = client.send(request, 
                    HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    return json.get("id").getAsString(); // UUID sin guiones
                } else if (response.statusCode() == 204 || response.statusCode() == 404) {
                    return null; // Usuario no existe
                }
            } catch (Exception e) {
                System.err.println("Error consultando API de Mojang: " + e.getMessage());
            }
            return null;
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
