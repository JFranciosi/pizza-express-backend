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
    private static final int CHAIN_LENGTH = 10000;

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
        String currentHash = UUID.randomUUID().toString();
        valueCommands.set(SEED_KEY, currentHash).await().indefinitely();
        for (int i = 0; i < count; i++) {
            currentHash = sha256(currentHash);
            listCommands.lpush(CHAIN_KEY, currentHash).await().indefinitely();
        }
    }

    public io.smallrye.mutiny.Uni<String> popNextHash() {
        return listCommands.lpop(CHAIN_KEY)
                .flatMap(hash -> {
                    if (hash == null) {
                        LOG.warn("Hash Chain exhausted! Regenerating...");
                        generateChain(CHAIN_LENGTH);
                        return listCommands.lpop(CHAIN_KEY);
                    }
                    return io.smallrye.mutiny.Uni.createFrom().item(hash);
                });
    }

    public double calculateCrashPoint(String hash) {
        String hex = hash.substring(0, 13);
        long h = Long.parseLong(hex, 16);
        double e = Math.pow(2, 52);
        double x = (double) h / e;
        double multiplier = 0.99 / (1.0 - x);

        if (multiplier < 1.00)
            multiplier = 1.00;

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
