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

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public void placeBet(String userId, String username, double amount, double autoCashout) {
        Game game = gameEngine.getCurrentGame();

        // 1. Validazioni preliminari (Fail fast)
        if (game == null || game.getStatus() != GameState.WAITING) {
            throw new IllegalStateException(
                    "Non puoi scommettere ora. Il gioco è " + (game != null ? game.getStatus() : "null"));
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("L'importo deve essere positivo");
        }
        if (amount < 0.10) {
            throw new IllegalArgumentException("L'importo minimo è 0.10€");
        }
        if (amount > 100) {
            throw new IllegalArgumentException("L'importo massimo è 100€");
        }

        // 2. Atomic check-and-act
        currentRoundBets.compute(userId, (key, existingBet) -> {
            if (existingBet != null) {
                throw new IllegalStateException("Hai già piazzato una scommessa per questo round");
            }

            // Controllo saldo e prelievo reale
            com.model.Player player = playerRepository.findById(userId);
            if (player == null) {
                throw new IllegalStateException("Utente non trovato");
            }
            if (player.getBalance() < amount) {
                throw new IllegalStateException("Saldo insufficiente");
            }

            // Arrotondamento
            double finalAmount = round(amount);
            double newBalance = round(player.getBalance() - finalAmount);

            player.setBalance(newBalance);
            playerRepository.save(player);

            LOG.info("Prelievo di " + finalAmount + " per l'utente " + username + ". Nuovo saldo: "
                    + player.getBalance());

            Bet bet = new Bet(userId, username, game.getId(), finalAmount);
            bet.setAutoCashout(autoCashout);
            return bet;
        });

        LOG.info("Scommessa piazzata: " + username + " - " + amount + "€ (Auto: " + autoCashout + "x)");
    }

    public void checkAutoCashouts(double currentMultiplier) {
        currentRoundBets.values().forEach(bet -> {
            if (bet.getCashOutMultiplier() == 0 && bet.getAutoCashout() > 1.00
                    && currentMultiplier >= bet.getAutoCashout()) {
                try {
                    cashOut(bet.getUserId());
                    LOG.info("AUTO-CASHOUT eseguito per " + bet.getUsername() + " a " + currentMultiplier + "x");
                } catch (Exception e) {
                    LOG.error("Errore durante auto-cashout per " + bet.getUsername(), e);
                }
            }
        });
    }

    public CashOutResult cashOut(String userId) {
        Game game = gameEngine.getCurrentGame();

        // 1. Validazioni
        if (game == null || game.getStatus() != GameState.FLYING) {
            throw new IllegalStateException("Impossibile fare cashout. Gioco non attivo.");
        }

        Bet bet = currentRoundBets.get(userId);
        if (bet == null) {
            throw new IllegalStateException("Nessuna scommessa attiva trovata per questo utente.");
        }
        if (bet.getCashOutMultiplier() > 0) {
            throw new IllegalStateException("Hai già incassato!");
        }

        // 2. Calcolo vincita
        double currentMultiplier = game.getMultiplier();
        double winAmount = round(bet.getAmount() * currentMultiplier);
        double profit = round(winAmount - bet.getAmount());

        // 3. Aggiorna stato scommessa
        bet.setCashOutMultiplier(currentMultiplier);
        bet.setProfit(profit);

        // 4. Accredito vincita reale
        com.model.Player player = playerRepository.findById(userId);
        if (player != null) {
            double newBalance = round(player.getBalance() + winAmount);
            player.setBalance(newBalance);
            playerRepository.save(player);
            LOG.info("CASHOUT SUCCESSO! " + userId + " vince " + String.format("%.2f", winAmount) + "€ ("
                    + currentMultiplier + "x). Nuovo saldo: " + player.getBalance());
            return new CashOutResult(winAmount, player.getBalance(), currentMultiplier);
        } else {
            LOG.error("Impossibile accreditare vincita, utente non trovato: " + userId);
            throw new IllegalStateException("Utente non trovato");
        }
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

    public void cancelBet(String userId) {
        Game game = gameEngine.getCurrentGame();

        // 1. Validazioni
        if (game == null || game.getStatus() != GameState.WAITING) {
            throw new IllegalStateException("Non puoi cancellare la scommessa ora. Il gioco è già partito.");
        }

        Bet bet = currentRoundBets.get(userId);
        if (bet == null) {
            throw new IllegalStateException("Nessuna scommessa da cancellare.");
        }

        // 2. Rimozione e rimborso
        currentRoundBets.remove(userId);

        com.model.Player player = playerRepository.findById(userId);
        if (player != null) {
            double newBalance = round(player.getBalance() + bet.getAmount());
            player.setBalance(newBalance);
            playerRepository.save(player);
            LOG.info("Scommessa cancellata per " + userId + ". Rimborso: " + bet.getAmount() + "€. Nuovo saldo: "
                    + player.getBalance());
        }
    }

    public List<Bet> resetBetsForNewRound() {
        // Ritorna le scommesse del round appena finito per archivio/storico
        List<Bet> oldBets = currentRoundBets.values().stream().collect(Collectors.toList());
        currentRoundBets.clear();
        return oldBets;
    }

    public Map<String, Bet> getCurrentBets() {
        return currentRoundBets;
    }
}
