package com.service;

import com.model.Bet;
import com.model.Game;
import com.model.GameState;
import com.repository.PlayerRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class BettingService {

    private static final Logger LOG = Logger.getLogger(BettingService.class);

    // Cache locale delle scommesse per il round corrente: UserId -> Bet
    private final Map<String, Bet> currentRoundBets = new ConcurrentHashMap<>();

    @Inject
    PlayerRepository playerRepository;

    @Inject
    GameEngineService gameEngine;

    // Helper per generare la chiave composta (userId:index)
    private String getBetKey(String userId, int index) {
        return userId + ":" + index;
    }

    public void placeBet(String userId, String username, double amount, double autoCashout, int index) {
        Game game = gameEngine.getCurrentGame();

        // 1. Validazioni preliminari
        if (game == null || game.getStatus() != GameState.WAITING) {
            throw new IllegalStateException(
                    "Non puoi scommettere ora. Il gioco è " + (game != null ? game.getStatus() : "null"));
        }
        if (amount <= 0 || amount < 0.10) {
            throw new IllegalArgumentException("L'importo minimo è 0.10€");
        }
        if (amount > 100) {
            throw new IllegalArgumentException("L'importo massimo è 100€");
        }

        String betKey = getBetKey(userId, index);

        // 2. Atomic check-and-act
        currentRoundBets.compute(betKey, (key, existingBet) -> {
            if (existingBet != null) {
                throw new IllegalStateException("Hai già piazzato questa scommessa (" + index + ")");
            }

            com.model.Player player = playerRepository.findById(userId);
            if (player == null)
                throw new IllegalStateException("Utente non trovato");
            if (player.getBalance() < amount)
                throw new IllegalStateException("Saldo insufficiente");

            double finalAmount = round(amount);
            double newBalance = round(player.getBalance() - finalAmount);

            player.setBalance(newBalance);
            playerRepository.save(player);

            LOG.info("Prelievo " + finalAmount + " per " + username + " (Bet " + index + "). Nuovo saldo: "
                    + player.getBalance());

            Bet bet = new Bet(userId, username, game.getId(), finalAmount, index);
            bet.setAutoCashout(autoCashout);
            return bet;
        });

        LOG.info("Scommessa piazzata: " + username + " [" + index + "] - " + amount + "€");
        // Broadcast deve includere l'index per distinguerle nel frontend
        gameEngine.broadcast("BET:" + userId + ":" + username + ":" + amount + ":" + index);
    }

    public CashOutResult cashOut(String userId, int index) {
        Game game = gameEngine.getCurrentGame();
        if (game == null || game.getStatus() != GameState.FLYING) {
            throw new IllegalStateException("Impossibile fare cashout. Gioco non attivo.");
        }

        String betKey = getBetKey(userId, index);
        Bet bet = currentRoundBets.get(betKey);

        if (bet == null)
            throw new IllegalStateException("Nessuna scommessa attiva trovata (Index " + index + ")");
        if (bet.getCashOutMultiplier() > 0)
            throw new IllegalStateException("Hai già incassato questa scommessa!");

        double currentMultiplier = game.getMultiplier();
        double winAmount = round(bet.getAmount() * currentMultiplier);

        bet.setCashOutMultiplier(currentMultiplier);
        bet.setProfit(round(winAmount - bet.getAmount()));

        com.model.Player player = playerRepository.findById(userId);
        if (player != null) {
            double newBalance = round(player.getBalance() + winAmount);
            player.setBalance(newBalance);
            playerRepository.save(player);
            LOG.info("CASHOUT " + userId + " [" + index + "] vince " + winAmount + "€. Nuovo saldo: "
                    + player.getBalance());
            gameEngine.broadcast("CASHOUT:" + userId + ":" + currentMultiplier + ":" + winAmount + ":" + index);
            return new CashOutResult(winAmount, player.getBalance(), currentMultiplier);
        }
        throw new IllegalStateException("Utente non trovato");
    }

    public void checkAutoCashouts(double currentMultiplier) {
        currentRoundBets.values().forEach(bet -> {
            if (bet.getCashOutMultiplier() == 0 && bet.getAutoCashout() > 1.00
                    && currentMultiplier >= bet.getAutoCashout()) {
                try {
                    // Passa anche l'index del bet
                    cashOut(bet.getUserId(), bet.getIndex());
                    LOG.info("AUTO-CASHOUT per " + bet.getUsername() + " [" + bet.getIndex() + "] a "
                            + currentMultiplier + "x");
                } catch (Exception e) {
                    LOG.error("Errore autpcashout " + bet.getUsername(), e);
                }
            }
        });
    }

    public void cancelBet(String userId, int index) {
        Game game = gameEngine.getCurrentGame();
        if (game == null || game.getStatus() != GameState.WAITING) {
            throw new IllegalStateException("Non puoi cancellare la scommessa ora.");
        }

        String betKey = getBetKey(userId, index);
        Bet bet = currentRoundBets.get(betKey);

        if (bet == null)
            throw new IllegalStateException("Nessuna scommessa da cancellare (Index " + index + ")");

        currentRoundBets.remove(betKey);

        com.model.Player player = playerRepository.findById(userId);
        if (player != null) {
            double newBalance = round(player.getBalance() + bet.getAmount());
            player.setBalance(newBalance);
            playerRepository.save(player);
            LOG.info("Scommessa cancellata " + userId + " [" + index + "]. Rimborso: " + bet.getAmount());
            gameEngine.broadcast("CANCEL_BET:" + userId + ":" + index);
        }
    }

    public List<Bet> resetBetsForNewRound() {
        // Ritorna le scommesse del round appena finito per archivio/storico
        List<Bet> oldBets = currentRoundBets.values().stream().collect(Collectors.toList());
        currentRoundBets.clear();
        return oldBets;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public static class CashOutResult {
        public double winAmount;
        public double newBalance;
        public double multiplier;

        public CashOutResult(double winAmount, double newBalance, double multiplier) {
            this.winAmount = winAmount;
            this.newBalance = newBalance;
            this.multiplier = multiplier;
        }
    }

    public Map<String, Bet> getCurrentBets() {
        return currentRoundBets;
    }
}
