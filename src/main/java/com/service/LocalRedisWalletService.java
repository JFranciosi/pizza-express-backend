package com.service;

import com.model.Player;
import com.repository.PlayerRepository;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class LocalRedisWalletService implements WalletService {

    private static final Logger LOG = Logger.getLogger(LocalRedisWalletService.class);
    private static final String IDEMPOTENCY_PREFIX = "wallet:tx:";
    private static final long PROCESSED_TX_TTL_SECONDS = 86400; // 24 hours

    // Script Lua per PRENOTARE fondi (Reserve)
    // KEYS[1] = player:{userId} (Hash)
    // KEYS[2] = wallet:tx:{transactionId} (String)
    // KEYS[3] = player:zero_balance (Sorted Set)
    // ARGV[1] = userId (raw string)
    // ARGV[2] = amount
    // ARGV[3] = ttl (seconds)
    // ARGV[4] = now (timestamp for zero balance score)
    private static final String RESERVE_SCRIPT = """
            local userId = ARGV[1]
            local playerKey = KEYS[1]
            local txKey = KEYS[2]
            local zeroBalanceKey = KEYS[3]
            local amount = tonumber(ARGV[2])
            local txTtl = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])

            if redis.call('EXISTS', txKey) == 1 then
                return 'PROCESSED'
            end

            local balanceStr = redis.call('HGET', playerKey, 'balance')
            if not balanceStr then
                return 'USER_NOT_FOUND'
            end

            local currentBalance = tonumber(balanceStr)
            if currentBalance < amount then
                return 'INSUFFICIENT_FUNDS'
            end

            local newBalance = currentBalance - amount
            newBalance = math.floor(newBalance * 100 + 0.5) / 100

            redis.call('HSET', playerKey, 'balance', tostring(newBalance))
            redis.call('SET', txKey, 'PROCESSED', 'EX', txTtl)

            if newBalance < 0.10 then
                redis.call('ZADD', zeroBalanceKey, 'NX', now, userId)
            end

            return 'OK'
            """;

    // Script Lua per ACCREDITARE vincite/rimborsi (Credit)
    // KEYS[1] = player:{userId}
    // KEYS[2] = wallet:tx:{transactionId}
    // KEYS[3] = player:zero_balance
    // ARGV[1] = userId
    // ARGV[2] = amount
    // ARGV[3] = ttl
    private static final String CREDIT_SCRIPT = """
            local userId = ARGV[1]
            local playerKey = KEYS[1]
            local txKey = KEYS[2]
            local zeroBalanceKey = KEYS[3]
            local amount = tonumber(ARGV[2])
            local txTtl = tonumber(ARGV[3])

            if redis.call('EXISTS', txKey) == 1 then
                return 'PROCESSED'
            end

            local balanceStr = redis.call('HGET', playerKey, 'balance')
            if not balanceStr then
                return 'USER_NOT_FOUND'
            end

            local currentBalance = tonumber(balanceStr)
            local newBalance = currentBalance + amount
            newBalance = math.floor(newBalance * 100 + 0.5) / 100

            redis.call('HSET', playerKey, 'balance', tostring(newBalance))
            redis.call('SET', txKey, 'PROCESSED', 'EX', txTtl)

            if newBalance > 0 then
                redis.call('ZREM', zeroBalanceKey, userId)
            end

            return 'OK'
            """;

    private final RedisDataSource ds;
    private final PlayerRepository playerRepository;
    private final ConcurrentHashMap<String, String> scriptShaCache = new ConcurrentHashMap<>();

    @Inject
    public LocalRedisWalletService(RedisDataSource ds, PlayerRepository playerRepository) {
        this.ds = ds;
        this.playerRepository = playerRepository;

        try {
            loadScript(RESERVE_SCRIPT);
            loadScript(CREDIT_SCRIPT);
        } catch (Exception e) {
            LOG.warn("Impossibile pre-caricare gli script Redis all'avvio: " + e.getMessage());
        }
    }

    private String loadScript(String script) {
        return ds.execute("SCRIPT", "LOAD", script).toString();
    }

    @Override
    public boolean reserveFunds(String userId, double amount, String roundId, String transactionId) {
        List<String> keys = Arrays.asList(
                "player:" + userId,
                IDEMPOTENCY_PREFIX + transactionId,
                "player:zero_balance");
        List<String> args = Arrays.asList(
                userId,
                String.valueOf(amount),
                String.valueOf(PROCESSED_TX_TTL_SECONDS),
                String.valueOf(System.currentTimeMillis()));

        String result = executeScript(RESERVE_SCRIPT, keys, args);

        switch (result) {
            case "OK":
                LOG.info("Fondi riservati (Lua): " + amount + " per user " + userId + ". TX: " + transactionId);
                return true;
            case "PROCESSED":
                LOG.warn("Transazione " + transactionId + " già processata (Idempotency Hit)");
                return true;
            case "INSUFFICIENT_FUNDS":
                LOG.error("Fondi insufficienti per user " + userId);
                return false;
            case "USER_NOT_FOUND":
                LOG.error("Utente non trovato per reserveFunds: " + userId);
                return false;
            default:
                LOG.error("Risultato script sconosciuto: " + result);
                return false;
        }
    }

    @Override
    public boolean creditWinnings(String userId, double amount, String roundId, String transactionId) {
        return executeCredit(userId, amount, transactionId, "Vincita accreditata");
    }

    @Override
    public boolean refundBet(String userId, double amount, String roundId, String transactionId) {
        return executeCredit(userId, amount, transactionId, "Rimborso effettuato");
    }

    private boolean executeCredit(String userId, double amount, String transactionId, String logPrefix) {
        List<String> keys = Arrays.asList(
                "player:" + userId,
                IDEMPOTENCY_PREFIX + transactionId,
                "player:zero_balance");
        List<String> args = Arrays.asList(
                userId,
                String.valueOf(amount),
                String.valueOf(PROCESSED_TX_TTL_SECONDS));

        String result = executeScript(CREDIT_SCRIPT, keys, args);

        switch (result) {
            case "OK":
                LOG.info(logPrefix + " (Lua): " + amount + " per user " + userId + ". TX: " + transactionId);
                return true;
            case "PROCESSED":
                LOG.warn("Transazione " + transactionId + " già processata (Idempotency Hit)");
                return true;
            case "USER_NOT_FOUND":
                return false;
            default:
                LOG.error("Risultato script sconosciuto: " + result);
                return false;
        }
    }

    @Override
    public double getBalance(String userId) {
        Player player = playerRepository.findById(userId);
        return player != null ? player.getBalance() : 0.0;
    }

    private String executeScript(String scriptContent, List<String> keys, List<String> args) {
        String sha = scriptShaCache.computeIfAbsent(scriptContent, this::loadScript);
        try {
            return evalSha(sha, keys, args);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                LOG.warn("Script mancante (NOSCRIPT), ricaricamento in corso...");
                sha = loadScript(scriptContent);
                scriptShaCache.put(scriptContent, sha);
                return evalSha(sha, keys, args);
            }
            throw e;
        }
    }

    private String evalSha(String sha, List<String> keys, List<String> args) {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add(sha);
        cmdArgs.add(String.valueOf(keys.size()));
        cmdArgs.addAll(keys);
        cmdArgs.addAll(args);

        return ds.execute("EVALSHA", cmdArgs.toArray(new String[0])).toString();
    }
}
