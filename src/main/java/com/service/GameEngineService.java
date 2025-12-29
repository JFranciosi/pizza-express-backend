package com.service;

import com.model.Game;
import com.model.GameState;
import com.web.GameSocket;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.hash.ReactiveHashCommands;
import io.quarkus.redis.datasource.list.ReactiveListCommands;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class GameEngineService {

    private static final Logger LOG = Logger.getLogger(GameEngineService.class);

    private Game currentGame;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private boolean startingNewRound = false;

    private static final long WAITING_TIME_MS = 10000;
    private static final double GROWTH_RATE = 0.00006;

    private long roundStartTime;
    private long timerId;

    private final GameSocket gameSocket;
    private final BettingService bettingService;
    private final ProvablyFairService provablyFairService;
    private final Vertx vertx;
    private final ReactiveHashCommands<String, String, String> hashCommands;
    private final ReactiveListCommands<String, String> listCommands;

    @Inject
    public GameEngineService(ReactiveRedisDataSource ds,
            GameSocket gameSocket,
            BettingService bettingService,
            ProvablyFairService provablyFairService,
            Vertx vertx) {
        this.hashCommands = ds.hash(String.class);
        this.listCommands = ds.list(String.class);
        this.gameSocket = gameSocket;
        this.bettingService = bettingService;
        this.provablyFairService = provablyFairService;
        this.vertx = vertx;
    }

    @Startup
    void init() {
        if (currentGame == null) {
            startNewRound();
            // Avvia il loop di gioco ogni 50ms (20 tick al secondo)
            timerId = vertx.setPeriodic(50, id -> gameLoop());
        }
    }

    @PreDestroy
    void destroy() {
        vertx.cancelTimer(timerId);
    }

    private void startNewRound() {
        if (startingNewRound)
            return;
        startingNewRound = true;

        provablyFairService.popNextHash()
                .subscribe().with(gameSeed -> {
                    currentGame = new Game();
                    currentGame.setId(UUID.randomUUID().toString());
                    currentGame.setStatus(GameState.WAITING);
                    currentGame.setMultiplier(1.00);

                    double crashPoint = provablyFairService.calculateCrashPoint(gameSeed);

                    currentGame.setCrashPoint(crashPoint);
                    currentGame.setSecret(gameSeed);
                    currentGame.setHash(provablyFairService.sha256(gameSeed));

                    currentGame.setStartTime(System.currentTimeMillis() + WAITING_TIME_MS);

                    bettingService.resetBetsForNewRound();
                    saveGameToRedis();

                    LOG.info("Nuovo round creato: " + currentGame.getId()
                            + " - Crash: " + crashPoint + " (Seed: " + gameSeed + ")");

                    gameSocket.broadcast("STATE:WAITING");
                    gameSocket.broadcast("TIMER:" + (WAITING_TIME_MS / 1000));
                    gameSocket.broadcast("HASH:" + currentGame.getHash());

                    startingNewRound = false;
                }, t -> {
                    LOG.error("Failed to start new round", t);
                    startingNewRound = false;
                });
    }

    private long lastSecondsBroadcast = -1;

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
                if (now >= roundStartTime + 3000) {
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

        double rawMultiplier = Math.exp(GROWTH_RATE * timeElapsed);

        if (Double.isInfinite(rawMultiplier) || Double.isNaN(rawMultiplier)) {
            LOG.warn("Multiplier calculation overflow (Inf/NaN). Forcing crash.");
            rawMultiplier = 100000.0; // Force max cap
        }

        BigDecimal bd = new BigDecimal(rawMultiplier).setScale(2, RoundingMode.FLOOR);
        double currentMultiplier = bd.doubleValue();

        if (currentMultiplier >= currentGame.getCrashPoint()) {
            crash(currentGame.getCrashPoint());
        } else {
            currentGame.setMultiplier(currentMultiplier);

            vertx.executeBlocking(() -> {
                bettingService.checkAutoCashouts(currentMultiplier);
                return null;
            }).onFailure(t -> LOG.error("Auto-cashout error", t));

            gameSocket.broadcast("TICK:" + currentMultiplier);
        }
    }

    private void crash(double finalMultiplier) {
        currentGame.setStatus(GameState.CRASHED);
        currentGame.setMultiplier(finalMultiplier);
        running.set(false);
        roundStartTime = System.currentTimeMillis();

        saveGameToRedis();
        saveToHistory(finalMultiplier);

        LOG.info("CRASHED at " + finalMultiplier + "x 💥");
        gameSocket.broadcast("CRASH:" + finalMultiplier + ":" + currentGame.getSecret());
    }

    private void saveGameToRedis() {
        Map<String, String> data = new HashMap<>();
        data.put("id", currentGame.getId());
        data.put("status", currentGame.getStatus().name());
        data.put("multiplier", String.valueOf(currentGame.getMultiplier()));
        data.put("startTime", String.valueOf(currentGame.getStartTime()));
        if (currentGame.getHash() != null) {
            data.put("hash", currentGame.getHash());
        }

        if (currentGame.getStatus() == GameState.CRASHED) {
            data.put("crashPoint", String.valueOf(currentGame.getCrashPoint()));
            if (currentGame.getSecret() != null) {
                data.put("secret", currentGame.getSecret());
            } else {
                data.put("secret", "Unknown");
            }
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
        String historyEntry = crashPoint + ":" + currentGame.getSecret();
        listCommands.lpush("game:history", historyEntry)
                .chain(v -> listCommands.ltrim("game:history", 0, 199))
                .subscribe().with(v -> {
                }, t -> {
                    if (t.getMessage() == null || !t.getMessage().contains("Client is closed")) {
                        LOG.error("Errore salvataggio history su Redis", t);
                    }
                });
    }

    public Game getCurrentGame() {
        return currentGame;
    }

    public Uni<List<String>> getHistory() {
        return listCommands.lrange("game:history", 0, 199);
    }

    public Uni<List<String>> getFullHistory(int limit) {
        int max = Math.min(limit, 200);
        return listCommands.lrange("game:history", 0, max - 1);
    }

    public void broadcast(String message) {
        gameSocket.broadcast(message);
    }
}
