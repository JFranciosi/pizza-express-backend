package com.service;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.list.ReactiveListCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

@ApplicationScoped
public class ProvablyFairService {

    private static final Logger LOG = Logger.getLogger(ProvablyFairService.class);
    private static final String CHAIN_KEY = "provably:chain";
    private static final String SEED_KEY = "provably:seed";
    private static final int CHAIN_LENGTH = 10000; // For demo purposes, smaller chain

    private final ReactiveListCommands<String, String> listCommands;
    private final ReactiveValueCommands<String, String> valueCommands;

    @Inject
    public ProvablyFairService(ReactiveRedisDataSource ds) {
        this.listCommands = ds.list(String.class);
        this.valueCommands = ds.value(String.class);
    }

    @Startup
    void init() {
        checkAndGenerateChain();
    }

    private void checkAndGenerateChain() {
        // Check if chain exists
        Long size = listCommands.llen(CHAIN_KEY).await().indefinitely();
        if (size == 0) {
            LOG.info("Generating new Provably Fair Hash Chain of size " + CHAIN_LENGTH + "...");
            generateChain(CHAIN_LENGTH);
            LOG.info("Chain generation complete.");
        } else {
            LOG.info("Existing Hash Chain found. Size: " + size);
        }
    }

    public void generateChain(int count) {
        // Start with a random secret (The "Termination Seed")
        String currentHash = UUID.randomUUID().toString();

        // Save the termination seed strictly? Not needed for verification,
        // but good to keep track of where we started.
        valueCommands.set(SEED_KEY, currentHash).await().indefinitely();

        // Generate backwards: H(n) = SHA256(H(n+1))
        // But to pop them in order (1, 2, 3...), we want the last generated hash to be
        // the first used.
        // So we generate: Seed -> H100 -> H99 -> ... -> H1
        // And we push them to a Redis List.
        // If we LPUSH, the list will be [H1, H2, ..., H100, Seed]
        // Then we LPOP to get H1.

        for (int i = 0; i < count; i++) {
            currentHash = sha256(currentHash);
            // Push to head of list. Last generated (H1) will be at index 0.
            listCommands.lpush(CHAIN_KEY, currentHash).await().indefinitely();
        }
    }

    public io.smallrye.mutiny.Uni<String> popNextHash() {
        // LPOP returns the first element (Head)
        return listCommands.lpop(CHAIN_KEY)
                .flatMap(hash -> {
                    if (hash == null) {
                        LOG.warn("Hash Chain exhausted! Regenerating...");
                        // This is tricky async recursion.
                        // For now, let's assume we have seeds.
                        // In a real app we'd trigger regeneration and retry.
                        // But simpler: just return a failure or fallback.
                        // Let's synchronously generate if empty (blocking but rare event)
                        // Or better:
                        generateChain(CHAIN_LENGTH);
                        return listCommands.lpop(CHAIN_KEY);
                    }
                    return io.smallrye.mutiny.Uni.createFrom().item(hash);
                });
    }

    public double calculateCrashPoint(String hash) {
        // Formula: 0.99 * E / (E - H)
        // H must be uniform in [0, 1).
        // Standard implementation: Take first 52 bits of HMAC or Hash?
        // Since we use the Hash itself as the seed for this round:
        // Use the hash hex string to generate a double.

        // Implementation based on Bustabit/standard:
        // 1. Take 13 hex chars (52 bits)
        // 2. Convert to long
        // 3. Divide by 2^52 to get float 0-1

        String hex = hash.substring(0, 13); // First 13 chars
        long h = Long.parseLong(hex, 16);
        double e = Math.pow(2, 52);

        // Apply house edge 1% -> The formula usually already considers edge or we floor
        // it.
        // Simple formula: 0.99 / (1 - X) where X is uniform [0,1]
        // Let's implement independent logic matching the user request:
        // User requested: Multiplier = 0.99 * E / (E - H)
        // Where H is random uniform.
        // Our 'h' above is uniform in [0, 2^52). So h/e is uniform in [0, 1).

        // Let X = h / e.
        // Multiplier = 0.99 / (1 - X)

        double x = (double) h / e;
        double multiplier = 0.99 / (1.0 - x);

        if (multiplier < 1.00)
            multiplier = 1.00;

        // Cap at reasonable max (e.g. 10000x or 1M x) to avoid Infinity issues
        if (multiplier > 100000.0)
            multiplier = 100000.0;

        return Math.floor(multiplier * 100) / 100.0;
    }

    public String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // Using the same hex helper or extracting it to utility?
    // Copied for self-containment for now.
    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
