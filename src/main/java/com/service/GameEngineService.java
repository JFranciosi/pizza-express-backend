package com.service;

import com.model.Game;
import com.model.GameState;
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
    public GameEngineService() {
        // Inizializza il primo gioco
        startNewRound();
    }

    private void startNewRound() {
        currentGame = new Game();
        currentGame.setId(UUID.randomUUID().toString());
        currentGame.setStatus(GameState.WAITING);
        currentGame.setMultiplier(1.00);
        // TODO: Generare CrashPoint cryptographically fair
        currentGame.setCrashPoint(generateCrashPoint());
        currentGame.setStartTime(System.currentTimeMillis() + WAITING_TIME_MS);

        LOG.info("Nuovo round creato: " + currentGame.getId() + " - Crash Point: " + currentGame.getCrashPoint());
    }

    @Scheduled(every = "0.1s") // Game Loop: 10 tick al secondo
    void gameLoop() {
        if (currentGame == null)
            return;

        long now = System.currentTimeMillis();

        switch (currentGame.getStatus()) {
            case WAITING:
                if (now >= currentGame.getStartTime()) {
                    startGame();
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

        // TODO: Notifica WebSocket GAME_STARTED
    }

    private void updateMultiplier(long now) {
        long timeElapsed = now - roundStartTime;

        // Formula esponenziale: E^(k * t)
        // t in millisecondi
        double rawMultiplier = Math.exp(GROWTH_RATE * timeElapsed);

        // Arrotonda a 2 decimali
        BigDecimal bd = new BigDecimal(rawMultiplier).setScale(2, RoundingMode.FLOOR);
        double currentMultiplier = bd.doubleValue();

        // Controllo CRASH
        if (currentMultiplier >= currentGame.getCrashPoint()) {
            crash(currentGame.getCrashPoint());
        } else {
            currentGame.setMultiplier(currentMultiplier);
            // LOG.debug("Multiplier: " + currentMultiplier + "x");
            // TODO: Notifica WebSocket TICK
        }
    }

    private void crash(double finalMultiplier) {
        currentGame.setStatus(GameState.CRASHED);
        currentGame.setMultiplier(finalMultiplier);
        running.set(false);
        roundStartTime = System.currentTimeMillis(); // Riutilizziamo per il timer di restart

        LOG.info("CRASHED at " + finalMultiplier + "x 💥");
        // TODO: Notifica WebSocket CRASH
        // TODO: Gestisci tutte le scommesse non incassate come PERSE
    }

    // Algoritmo semplice per ora (da migliorare per fairness)
    private double generateCrashPoint() {
        // Genera un punto di crash casuale pesato verso il basso
        // Esempio basico: 1 / rand(0,1)

        double r = Math.random();
        // 3% di chance di crash istantaneo (1.00x)
        if (r < 0.03)
            return 1.00;

        // Formula classica: multiplier = 0.99 / (1 - random)
        // Ma limitiamolo per sicurezza
        double multiplier = 0.99 / (1 - r);

        if (multiplier < 1.00)
            multiplier = 1.00;
        if (multiplier > 100.00)
            multiplier = 100.00; // Cap provvisorio

        BigDecimal bd = new BigDecimal(multiplier).setScale(2, RoundingMode.FLOOR);
        return bd.doubleValue();
    }

    public Game getCurrentGame() {
        return currentGame;
    }
}
