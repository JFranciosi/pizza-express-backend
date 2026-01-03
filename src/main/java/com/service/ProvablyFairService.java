package com.service;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.list.ListCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

@ApplicationScoped
@Startup
public class ProvablyFairService {

    private static final Logger LOG = Logger.getLogger(ProvablyFairService.class);
    private static final String CHAIN_KEY = "fairness:chain"; // La lista degli hash
    private static final String COMMITMENT_KEY = "fairness:commit"; // L'hash pubblico iniziale
    private static final int CHAIN_LENGTH = 10000; // Adjusted to 10k as per user request

    private final ListCommands<String, String> listCommands;
    private final ValueCommands<String, String> valueCommands;
    private final KeyCommands<String> keyCommands;

    @Inject
    public ProvablyFairService(RedisDataSource ds) {
        this.listCommands = ds.list(String.class);
        this.valueCommands = ds.value(String.class);
        this.keyCommands = ds.key(String.class);
    }

    @PostConstruct
    void init() {
        if (valueCommands.get(COMMITMENT_KEY) == null) {
            LOG.info("Nessuna catena Provably Fair trovata (o invalidata). Generazione nuova catena sicura...");
            generateNewChain();
        }
    }

    /**
     * Genera una nuova Hash Chain usando SecureRandom e SHA-256.
     * Questa operazione è pesante, si fa all'avvio o quando la catena finisce.
     */
    public void generateNewChain() {
        keyCommands.del(CHAIN_KEY);

        SecureRandom random = new SecureRandom();
        byte[] seed = new byte[32];
        random.nextBytes(seed);

        String currentHash = HexFormat.of().formatHex(seed);
        List<String> chain = new ArrayList<>(CHAIN_LENGTH);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            for (int i = 0; i < CHAIN_LENGTH; i++) {
                byte[] hashBytes = digest.digest(currentHash.getBytes(StandardCharsets.UTF_8));
                currentHash = HexFormat.of().formatHex(hashBytes);
                chain.add(currentHash);
            }

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 non disponibile", e);
        }

        String publicCommitment = currentHash;
        valueCommands.set(COMMITMENT_KEY, publicCommitment);
        Collections.reverse(chain);
        listCommands.rpush(CHAIN_KEY, chain.toArray(new String[0]));
        LOG.info("Nuova catena Provably Fair generata. Lunghezza: " + CHAIN_LENGTH);
        LOG.info("PUBLIC COMMITMENT: " + publicCommitment);
    }

    /**
     * Estrae il prossimo hash dalla catena per la partita corrente.
     */
    public String nextGameHash() {
        String hash = listCommands.lpop(CHAIN_KEY);

        if (hash == null) {
            LOG.warn("Catena Provably Fair esaurita! Rigenerazione d'emergenza.");
            generateNewChain();
            return listCommands.lpop(CHAIN_KEY);
        }

        return hash;
    }

    public String getCurrentCommitment() {
        return valueCommands.get(COMMITMENT_KEY);
    }

    public long getRemainingGames() {
        return listCommands.llen(CHAIN_KEY);
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
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
