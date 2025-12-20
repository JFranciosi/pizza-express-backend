package com.repository;

import com.model.Player;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
public class PlayerRepository {

    private final HashCommands<String, String, String> hashCommands;
    private final ValueCommands<String, String> valueCommands;
    private final KeyCommands<String> keyCommands;

    public PlayerRepository(RedisDataSource ds) {
        this.hashCommands = ds.hash(String.class);
        this.valueCommands = ds.value(String.class);
        this.keyCommands = ds.key();
    }

    public void save(Player player) {
        String key = "player:" + player.getId();
        hashCommands.hset(key, Map.of(
                "id", player.getId(),
                "username", player.getUsername(),
                "email", player.getEmail(),
                "password", player.getPasswordHash(),
                "balance", String.valueOf(player.getBalance())));
        valueCommands.set("player:email:" + player.getEmail(), player.getId());
        valueCommands.set("player:username:" + player.getUsername(), player.getId());
    }

    public void removeLookups(String email, String username) {
        if (email != null)
            keyCommands.del("player:email:" + email);
        if (username != null)
            keyCommands.del("player:username:" + username);
    }

    public Player findById(String id) {
        Map<String, String> data = hashCommands.hgetall("player:" + id);
        if (data.isEmpty())
            return null;

        return new Player(
                data.get("id"),
                data.get("username"),
                data.get("email"),
                data.get("password"),
                Double.parseDouble(data.get("balance")));
    }

    public Player findByEmail(String email) {
        String idStr = valueCommands.get("player:email:" + email);
        if (idStr == null)
            return null;
        return findById(idStr);
    }

    public boolean existsByEmail(String email) {
        return valueCommands.get("player:email:" + email) != null;
    }

    public boolean existsByUsername(String username) {
        return valueCommands.get("player:username:" + username) != null;
    }

    public void ensureIndices(Player player) {
        // Idempotently ensure lookup keys exist
        valueCommands.set("player:email:" + player.getEmail(), player.getId());
        valueCommands.set("player:username:" + player.getUsername(), player.getId());
    }

    public void saveRefreshToken(String token, String playerId) {
        valueCommands.set("refresh_token:" + token, playerId, new SetArgs().ex(7200)); // 2 hours
    }

    public String validateRefreshToken(String token) {
        String playerId = valueCommands.get("refresh_token:" + token);
        if (playerId == null)
            return null;
        return playerId;
    }

    public void deleteRefreshToken(String token) {
        keyCommands.del("refresh_token:" + token);
    }
}
