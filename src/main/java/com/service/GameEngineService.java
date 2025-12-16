package com.service;

import com.model.Game;
import com.model.GameState;
import com.web.GameSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class GameEngineService {

    private static final Logger LOG = Logger.getLogger(GameEngineService.class);

    private Game currentGame;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Configurazione gioco
    private static final long WAITING_TIME_MS = 10000; // 10 secondi di attesa
    private static final double GROWTH_RATE = 0.00006; // Velocità di crescita (da calibrare)

    // Stato interno per il loop
    private long roundStartTime;
    private long timerId;

    @Inject
    GameSocket gameSocket;

    @Inject
    BettingService bettingService;

    @Inject
    io.vertx.core.Vertx vertx;

    private final io.quarkus.redis.datasource.hash.ReactiveHashCommands<String, String, String> hashCommands;
    private final io.quarkus.redis.datasource.list.ReactiveListCommands<String, String> listCommands;

    @Inject
    public GameEngineService(io.quarkus.redis.datasource.ReactiveRedisDataSource ds) {
        this.hashCommands = ds.hash(String.class);
        this.listCommands = ds.list(String.class);
    }

    @io.quarkus.runtime.Startup
    void init() {
        if (currentGame == null) {
            startNewRound();
            // Avvia il loop di gioco ogni 100ms
            timerId = vertx.setPeriodic(100, id -> gameLoop());
        }
    }

    @jakarta.annotation.PreDestroy
    void destroy() {
        vertx.cancelTimer(timerId);
    }

    private void startNewRound() {
        currentGame = new Game();
        currentGame.setId(UUID.randomUUID().toString());
        currentGame.setStatus(GameState.WAITING);
        currentGame.setMultiplier(1.00);
        double crashPoint = generateCrashPoint();
        currentGame.setCrashPoint(crashPoint);

        // Provably Fair: Genera secret e hash
        String secret = UUID.randomUUID().toString();
        String hash = generateHash(secret, crashPoint);
        currentGame.setSecret(secret);
        currentGame.setHash(hash);

        currentGame.setStartTime(System.currentTimeMillis() + WAITING_TIME_MS);

        bettingService.resetBetsForNewRound();
        saveGameToRedis();

        LOG.info("Nuovo round creato: " + currentGame.getId()
                + " - Hash: " + currentGame.getHash());
        gameSocket.broadcast("STATE:WAITING");
        gameSocket.broadcast("TIMER:" + (WAITING_TIME_MS / 1000));
    }

    private long lastSecondsBroadcast = -1;

    // Rimosso @Scheduled, ora chiamato da Vert.x
    void gameLoop() {
        if (currentGame == null)
            return;

        long now = System.currentTimeMillis();

        switch (currentGame.getStatus()) {
            case WAITING:
                if (now >= currentGame.getStartTime()) {
                    startGame();
                } else {
                    long remainingMs = currentGame.getStartTime() - now;
                    long remainingSeconds = remainingMs / 1000;
                    if (remainingSeconds != lastSecondsBroadcast) {
                        gameSocket.broadcast("TIMER:" + remainingSeconds);
                        lastSecondsBroadcast = remainingSeconds;
                    }
                }
                break;

            case FLYING:
                updateMultiplier(now);
                break;

            case CRASHED:
                if (now >= roundStartTime + 1000) { // 3 secondi di pausa post-crash
                    startNewRound();
                }
                break;
        }
    }

    private void startGame() {
        currentGame.setStatus(GameState.FLYING);
        roundStartTime = System.currentTimeMillis();
        running.set(true);
        saveGameToRedis();
        LOG.info("Game Started! VESPA IN VOLO 🛵💨");

        gameSocket.broadcast("STATE:RUNNING");
        gameSocket.broadcast("TAKEOFF");
    }

    private void updateMultiplier(long now) {
        long timeElapsed = now - roundStartTime;

        // Formula esponenziale: E^(k * t)
        double rawMultiplier = Math.exp(GROWTH_RATE * timeElapsed);

        BigDecimal bd = new BigDecimal(rawMultiplier).setScale(2, RoundingMode.FLOOR);
        double currentMultiplier = bd.doubleValue();

        if (currentMultiplier >= currentGame.getCrashPoint()) {
            crash(currentGame.getCrashPoint());
        } else {
            currentGame.setMultiplier(currentMultiplier);
            // Non salviamo su Redis ad ogni tick per evitare overhead eccessivo,
            // ma se serve real-time per chi entra dopo, si può decommentare:
            // saveGameToRedis();

            gameSocket.broadcast("TICK:" + currentMultiplier);
        }
    }

    private void crash(double finalMultiplier) {
        currentGame.setStatus(GameState.CRASHED);
        currentGame.setMultiplier(finalMultiplier);
        running.set(false);
        roundStartTime = System.currentTimeMillis(); // Reset timer per restart

        saveGameToRedis();
        saveToHistory(finalMultiplier);

        LOG.info("CRASHED at " + finalMultiplier + "x 💥");
        gameSocket.broadcast("CRASH:" + finalMultiplier);
    }

    private void saveGameToRedis() {
        java.util.Map<String, String> data = new java.util.HashMap<>();
        data.put("id", currentGame.getId());
        data.put("status", currentGame.getStatus().name());
        data.put("multiplier", String.valueOf(currentGame.getMultiplier()));
        data.put("startTime", String.valueOf(currentGame.getStartTime()));
        data.put("hash", currentGame.getHash());

        if (currentGame.getStatus() == GameState.CRASHED) {
            data.put("crashPoint", String.valueOf(currentGame.getCrashPoint()));
            data.put("secret", currentGame.getSecret());
        } else {
            data.put("crashPoint", "HIDDEN");
        }

        hashCommands.hset("game:current", data)
                .subscribe().with(v -> {
                }, t -> {
                    if (t.getMessage() == null || !t.getMessage().contains("Client is closed")) {
                        LOG.error("Errore salvataggio game su Redis", t);
                    }
                });
    }

    private void saveToHistory(double crashPoint) {
        listCommands.lpush("game:history", String.valueOf(crashPoint))
                .chain(v -> listCommands.ltrim("game:history", 0, 49))
                .subscribe().with(v -> {
                }, t -> {
                    if (t.getMessage() == null || !t.getMessage().contains("Client is closed")) {
                        LOG.error("Errore salvataggio history su Redis", t);
                    }
                });
    }

    private double generateCrashPoint() {
        double r = Math.random();
        if (r < 0.03)
            return 1.00;

        double multiplier = 0.99 / (1 - r);

        if (multiplier < 1.00)
            multiplier = 1.00;
        if (multiplier > 100.00)
            multiplier = 100.00;

        BigDecimal bd = new BigDecimal(multiplier).setScale(2, RoundingMode.FLOOR);
        return bd.doubleValue();
    }

    private String generateHash(String secret, double crashPoint) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            String toHash = secret + ":" + crashPoint;
            byte[] encodedhash = digest.digest(toHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return bytesToHex(encodedhash);
        } catch (Exception e) {
            throw new RuntimeException("Errore SHA-256", e);
        }
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (int i = 0; i < hash.length; i++) {
            String hex = Integer.toHexString(0xff & hash[i]);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public Game getCurrentGame() {
        return currentGame;
    }

    public io.smallrye.mutiny.Uni<java.util.List<String>> getHistory() {
        return listCommands.lrange("game:history", 0, 9);
    }

    public io.smallrye.mutiny.Uni<java.util.List<String>> getFullHistory() {
        return listCommands.lrange("game:history", 0, 49);
    }
}
