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

    public void placeBet(String userId, String username, double amount) {
        Game game = gameEngine.getCurrentGame();

        // 1. Validazioni
        if (game == null || game.getStatus() != GameState.WAITING) {
            throw new IllegalStateException(
                    "Non puoi scommettere ora. Il gioco è " + (game != null ? game.getStatus() : "null"));
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("L'importo deve essere positivo");
        }
        // MAX BET 100 EURO
        if (amount > 100) {
            throw new IllegalArgumentException("L'importo massimo è 100€");
        }

        if (currentRoundBets.containsKey(userId)) {
            throw new IllegalStateException("Hai già piazzato una scommessa per questo round");
        }

        // 2. Controllo saldo e prelievo reale
        com.model.Player player = playerRepository.findById(userId);
        if (player == null) {
            throw new IllegalStateException("Utente non trovato");
        }
        if (player.getBalance() < amount) {
            throw new IllegalStateException("Saldo insufficiente");
        }

        player.setBalance(player.getBalance() - amount);
        playerRepository.save(player);

        LOG.info("Prelievo di " + amount + " per l'utente " + username + ". Nuovo saldo: " + player.getBalance());

        // 3. Registra scommessa
        Bet bet = new Bet(userId, username, game.getId(), amount);
        currentRoundBets.put(userId, bet);

        LOG.info("Scommessa piazzata: " + username + " - " + amount + "€");
    }

    public void cashOut(String userId) {
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
        double winAmount = bet.getAmount() * currentMultiplier;
        double profit = winAmount - bet.getAmount();

        // 3. Aggiorna stato scommessa
        bet.setCashOutMultiplier(currentMultiplier);
        bet.setProfit(profit);

        // 4. Accredito vincita reale
        com.model.Player player = playerRepository.findById(userId);
        if (player != null) {
            player.setBalance(player.getBalance() + winAmount);
            playerRepository.save(player);
            LOG.info("CASHOUT SUCCESSO! " + userId + " vince " + String.format("%.2f", winAmount) + "€ ("
                    + currentMultiplier + "x). Nuovo saldo: " + player.getBalance());
        } else {
            LOG.error("Impossibile accreditare vincita, utente non trovato: " + userId);
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
