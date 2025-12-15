package com.service;

import com.model.Game;
import com.model.GameState;
import com.web.GameSocket;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
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
    private static final long WAITING_TIME_MS = 5000; // 5 secondi di attesa
    private static final double GROWTH_RATE = 0.00006; // Velocità di crescita (da calibrare)

    // Stato interno per il loop
    private long roundStartTime;

    @Inject
    GameSocket gameSocket;

    @Inject
    BettingService bettingService;

    @Inject
    io.vertx.core.Vertx vertx;

    @Inject
    public GameEngineService() {
    }

    @io.quarkus.runtime.Startup
    void init() {
        if (currentGame == null) {
            startNewRound();
            // Avvia il loop di gioco ogni 100ms
            vertx.setPeriodic(100, id -> gameLoop());
        }
    }

    private void startNewRound() {
        currentGame = new Game();
        currentGame.setId(UUID.randomUUID().toString());
        currentGame.setStatus(GameState.WAITING);
        currentGame.setMultiplier(1.00);
        currentGame.setCrashPoint(generateCrashPoint());
        currentGame.setStartTime(System.currentTimeMillis() + WAITING_TIME_MS);

        bettingService.resetBetsForNewRound();

        LOG.info("Nuovo round creato: " + currentGame.getId() + " - Crash Point: " + currentGame.getCrashPoint());
        gameSocket.broadcast("STATE:WAITING");
        gameSocket.broadcast("TIMER:" + (WAITING_TIME_MS / 1000));
    }

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
                    // Opzionale: broadcast countdown
                }
                break;

            case FLYING:
                updateMultiplier(now);
                break;

            case CRASHED:
                // Dopo un po' di tempo che è crashato, riavvia
                if (now >= roundStartTime + 3000) { // 3 secondi di pausa post-crash
                    startNewRound();
                }
                break;
        }
    }

    private void startGame() {
        currentGame.setStatus(GameState.FLYING);
        roundStartTime = System.currentTimeMillis();
        running.set(true);
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
            // Ottimizzazione: Manda tick solo se cambiato significativamente o ogni tot
            gameSocket.broadcast("TICK:" + currentMultiplier);
        }
    }

    private void crash(double finalMultiplier) {
        currentGame.setStatus(GameState.CRASHED);
        currentGame.setMultiplier(finalMultiplier);
        running.set(false);
        roundStartTime = System.currentTimeMillis(); // Reset timer per restart

        LOG.info("CRASHED at " + finalMultiplier + "x 💥");
        gameSocket.broadcast("CRASH:" + finalMultiplier);
    }

    private double generateCrashPoint() {
        double r = Math.random();
        if (r < 0.03)
            return 1.00; // 3% House Edge istantaneo

        double multiplier = 0.99 / (1 - r);

        if (multiplier < 1.00)
            multiplier = 1.00;
        if (multiplier > 100.00)
            multiplier = 100.00;

        BigDecimal bd = new BigDecimal(multiplier).setScale(2, RoundingMode.FLOOR);
        return bd.doubleValue();
    }

    public Game getCurrentGame() {
        return currentGame;
    }
}
