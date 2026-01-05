package com.service;

import com.model.Game;
import com.model.GameState;
import com.web.GameSocket;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.list.ListCommands;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@ApplicationScoped
public class GameEngineService {

    private static final Logger LOG = Logger.getLogger(GameEngineService.class);

    private Game currentGame;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private boolean startingNewRound = false;
    private final ReentrantLock gameLock = new ReentrantLock();

    private static final long WAITING_TIME_MS = 10000;
    private static final double GROWTH_RATE = 0.00006;

    private long roundStartTime;

    private final GameSocket gameSocket;
    private final BettingService bettingService;
    private final ProvablyFairService provablyFairService;
    private final HashCommands<String, String, String> hashCommands;
    private final ListCommands<String, String> listCommands;

    @Inject
    public GameEngineService(RedisDataSource ds,
            GameSocket gameSocket,
            BettingService bettingService,
            ProvablyFairService provablyFairService) {
        this.hashCommands = ds.hash(String.class);
        this.listCommands = ds.list(String.class);
        this.gameSocket = gameSocket;
        this.bettingService = bettingService;
        this.provablyFairService = provablyFairService;
    }

    @Startup
    void init() {
        if (currentGame == null) {
            startNewRound();
        }
    }

    @Scheduled(every = "0.05s")
    @RunOnVirtualThread
    void gameLoop() {
        if (currentGame == null || startingNewRound)
            return;

        long now = System.currentTimeMillis();

        switch (currentGame.getStatus()) {
            case WAITING -> {
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
            }
            case FLYING -> updateMultiplier(now);
            case CRASHED -> {
                if (now >= roundStartTime + 3000) {
                    startNewRound();
                }
            }
        }
    }

    private void startNewRound() {
        if (startingNewRound)
            return;
        startingNewRound = true;

        try {
            String gameSeed = provablyFairService.nextGameHash();
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

            LOG.info("Nuovo round creato: " + currentGame.getId() + " - Hash: " + currentGame.getHash());

            gameSocket.broadcast("STATE:WAITING");
            gameSocket.broadcast("TIMER:" + (WAITING_TIME_MS / 1000));
            gameSocket.broadcast("HASH:" + currentGame.getHash());

        } catch (Exception e) {
            LOG.error("Failed to start new round", e);
        } finally {
            startingNewRound = false;
        }
    }

    private long lastSecondsBroadcast = -1;

    private void startGame() {
        gameLock.lock();
        try {
            currentGame.setStatus(GameState.FLYING);
            roundStartTime = System.currentTimeMillis();
            running.set(true);
            saveGameToRedis();
            LOG.info("Game Started! VESPA IN VOLO 🛵💨");

            gameSocket.broadcast("STATE:RUNNING");
            gameSocket.broadcast("TAKEOFF");
        } finally {
            gameLock.unlock();
        }
    }

    public void runInLock(Runnable action) {
        gameLock.lock();
        try {
            action.run();
        } finally {
            gameLock.unlock();
        }
    }

    private void updateMultiplier(long now) {
        long timeElapsed = now - roundStartTime;
        double rawMultiplier = Math.exp(GROWTH_RATE * timeElapsed);

        if (Double.isInfinite(rawMultiplier) || Double.isNaN(rawMultiplier)) {
            LOG.warn("Multiplier calculation overflow (Inf/NaN). Forcing crash.");
            rawMultiplier = 100000.0;
        }

        BigDecimal bd = new BigDecimal(rawMultiplier).setScale(2, RoundingMode.FLOOR);
        double currentMultiplier = bd.doubleValue();

        if (currentMultiplier >= currentGame.getCrashPoint()) {
            crash(currentGame.getCrashPoint());
        } else {
            currentGame.setMultiplier(currentMultiplier);
            bettingService.checkAutoCashouts(currentMultiplier);
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
        try {
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
                data.put("secret", currentGame.getSecret() != null ? currentGame.getSecret() : "Unknown");
            } else {
                data.put("crashPoint", "HIDDEN");
            }

            hashCommands.hset("game:current", data);
        } catch (Exception e) {
            LOG.error("Errore salvataggio game su Redis", e);
        }
    }

    private void saveToHistory(double crashPoint) {
        try {
            String historyEntry = crashPoint + ":" + currentGame.getSecret();
            listCommands.lpush("game:history", historyEntry);
            listCommands.ltrim("game:history", 0, 199);
        } catch (Exception e) {
            LOG.error("Errore salvataggio history su Redis", e);
        }
    }

    public Game getCurrentGame() {
        return currentGame;
    }

    public List<String> getHistory() {
        return listCommands.lrange("game:history", 0, 199);
    }

    public List<String> getFullHistory(int limit) {
        int max = Math.min(limit, 200);
        return listCommands.lrange("game:history", 0, max - 1);
    }

    public long getRoundStartTime() {
        return roundStartTime;
    }

    public void broadcast(String message) {
        gameSocket.broadcast(message);
    }
}
