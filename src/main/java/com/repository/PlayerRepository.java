package com.repository;

import com.model.Player;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.sortedset.ZAddArgs;
import io.quarkus.redis.datasource.sortedset.ScoreRange;
import io.quarkus.redis.datasource.sortedset.ScoredValue;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PlayerRepository {

    private static final Logger LOG = Logger.getLogger(PlayerRepository.class);

    private final HashCommands<String, String, String> hashCommands;
    private final ValueCommands<String, String> valueCommands;
    private final KeyCommands<String> keyCommands;
    private final io.quarkus.redis.datasource.sortedset.SortedSetCommands<String, String> sortedSetCommands;

    public PlayerRepository(RedisDataSource ds) {
        this.hashCommands = ds.hash(String.class);
        this.valueCommands = ds.value(String.class);
        this.keyCommands = ds.key();
        this.sortedSetCommands = ds.sortedSet(String.class);
    }

    @SuppressWarnings("unchecked")
    public void markZeroBalance(String playerId) {
        sortedSetCommands.zadd("player:zero_balance", new ZAddArgs().nx(),
                ScoredValue.of(playerId, (double) System.currentTimeMillis()));
    }

    public void clearZeroBalance(String playerId) {
        sortedSetCommands.zrem("player:zero_balance", playerId);
    }

    public java.util.List<String> findEligibleForRefill(long cutoffTime) {
        return sortedSetCommands.zrangebyscore("player:zero_balance",
                ScoreRange.from(0.0, cutoffTime));
    }

    public void save(Player player) {
        String key = "player:" + player.getId();
        java.util.Map<String, String> data = new java.util.HashMap<>();
        data.put("id", player.getId());
        data.put("username", player.getUsername());
        data.put("email", player.getEmail());
        data.put("password", player.getPasswordHash());
        data.put("balance", String.valueOf(player.getBalance()));
        if (player.getAvatarUrl() != null) {
            data.put("avatarUrl", player.getAvatarUrl());
        }

        hashCommands.hset(key, data);
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

        Player player = new Player(
                data.get("id"),
                data.get("username"),
                data.get("email"),
                data.get("password"),
                Double.parseDouble(data.get("balance")));

        if (data.containsKey("avatarUrl")) {
            player.setAvatarUrl(data.get("avatarUrl"));
        }
        return player;
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

    public void saveResetToken(String token, String playerId) {
        valueCommands.set("reset_token:" + token, playerId, new SetArgs().ex(900)); // 15 minutes
    }

    public String validateResetToken(String token) {
        String playerId = valueCommands.get("reset_token:" + token);
        if (playerId == null)
            return null;
        return playerId;
    }

    public void deleteResetToken(String token) {
        keyCommands.del("reset_token:" + token);
    }

    public String scanAndFixZeroBalances() {
        StringBuilder report = new StringBuilder();
        int count = 0;
        Iterable<String> keys = keyCommands.keys("player:*");
        report.append("Starting scan...\n");

        for (String key : keys) {
            // Ignore lookup keys
            if (key.contains(":email:") || key.contains(":username:")) {
                // report.append("Skipped lookup: ").append(key).append("\n");
                continue;
            }

            try {
                String balanceStr = hashCommands.hget(key, "balance");

                if (balanceStr != null) {
                    double balance = Double.parseDouble(balanceStr);
                    if (balance < 0.10) {
                        String playerId = key.substring(7);
                        markZeroBalance(playerId);
                        count++;
                        report.append("✅ QUEUED: ").append(key).append(" (Bal: ").append(balanceStr).append(")\n");
                    } else {
                        report.append("➖ IGNORED: ").append(key).append(" (Bal: ").append(balanceStr).append(")\n");
                    }
                } else {
                    report.append("❓ NO BALANCE: ").append(key).append("\n");
                }
            } catch (Exception e) {
                report.append("❌ ERROR: ").append(key).append(" - ").append(e.getMessage()).append("\n");
            }
        }
        report.append("Done. Queued ").append(count).append(" users.");
        return report.toString();
    }
}
