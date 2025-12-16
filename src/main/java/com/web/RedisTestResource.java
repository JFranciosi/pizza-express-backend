package com.web;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/test-redis")
public class RedisTestResource {

    private final ValueCommands<String, String> commands;

    public RedisTestResource(RedisDataSource ds) {
        this.commands = ds.value(String.class);
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String checkConnection() {
        try {
            commands.set("test-key", "Funziona!");
            String value = commands.get("test-key");
            return "✅ SUCCESSO! Redis è connesso.\n" +
                    "Ho scritto e riletto: " + value;

        } catch (Exception e) {
            return "❌ ERRORE: Non riesco a collegarmi a Redis.\n" +
                    "Dettaglio: " + e.getMessage();
        }
    }
}