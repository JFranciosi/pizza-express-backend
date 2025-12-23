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

    @Inject
    WalletService walletService; // [NEW] Seamless Wallet Injection

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

            double finalAmount = round(amount);
            String txId = java.util.UUID.randomUUID().toString(); // [NEW] Idempotency Key

            // [MODIFIED] Use WalletService to reserve funds
            boolean success = walletService.reserveFunds(userId, finalAmount, game.getId(), txId);

            if (!success) {
                // Check specific reason? For now assume balance.
                // We could check balance via walletService.getBalance(userId) if needed
                throw new IllegalStateException("Saldo insufficiente o errore transazione");
            }

            // Retrieve avatar URL using player repository explicitly if needed, or better,
            // pass it?
            // Since we only have userId/username here, we might need a quick lookup if we
            // want avatar.
            // Or we assume the client sends it? Client sending it is spoofable.
            // Better to fetch player.
            com.model.Player player = playerRepository.findById(userId);
            String avatarUrl = (player != null) ? player.getAvatarUrl() : null;

            Bet bet = new Bet(userId, username, game.getId(), finalAmount, index, avatarUrl);
            bet.setAutoCashout(autoCashout);
            return bet;
        });

        // Retrieve the created bet to get the avatarUrl (in case we want to be sure)
        // actually we have it in the scope.
        // We need to broadcast the avatarUrl too.
        // Protocol: BET:userId:username:amount:index:avatarUrl
        // Wait, existing protocol is position based.
        // Let's modify broadcast to include it at the end?

        String avatarUrl = currentRoundBets.get(betKey).getAvatarUrl();
        // Use the API URL if avatar exists
        String avatarApiUrl = (avatarUrl != null && !avatarUrl.isEmpty()) ? "/users/" + userId + "/avatar" : "";

        LOG.info("Scommessa piazzata: " + username + " [" + index + "] - " + amount + "€");
        // Broadcast user URL
        gameEngine.broadcast("BET:" + userId + ":" + username + ":" + amount + ":" + index + ":" + avatarApiUrl);
    }

    public CashOutResult cashOut(String userId, int index) {
        return cashOut(userId, index, null);
    }

    public CashOutResult cashOut(String userId, int index, Double targetMultiplier) {
        Game game = gameEngine.getCurrentGame();
        // Se targetMultiplier è specificato (auto-cashout), permettiamo anche se il
        // gioco sta tecnicamente crashando/finendo
        // ma controlliamo che il moltiplicatore raggiunto sia sufficiente
        if (game == null) {
            throw new IllegalStateException("Impossibile fare cashout. Gioco non attivo.");
        }

        // Se è manuale, deve essere FLYING. Se è auto, potrebbe essere appena crashato
        // ma il check è avvenuto prima?
        // Per sicurezza manteniamo il check su FLYING o checkiamo se il crash point >
        // target
        if (game.getStatus() != GameState.FLYING && targetMultiplier == null) {
            throw new IllegalStateException("Impossibile fare cashout. Gioco non attivo.");
        }

        String betKey = getBetKey(userId, index);
        Bet bet = currentRoundBets.get(betKey);

        if (bet == null)
            throw new IllegalStateException("Nessuna scommessa attiva trovata (Index " + index + ")");
        if (bet.getCashOutMultiplier() > 0)
            throw new IllegalStateException("Hai già incassato questa scommessa!");

        // [MODIFIED] Esecuzione immediata (istantanea)
        if (targetMultiplier == null) {
            // Manual Cashout: Execute IMMEDIATELY using current game multiplier
            return executeCashoutLogically(userId, index, game.getMultiplier());
        } else {
            // Auto-Cashout: Executed by GameEngine loop
            return executeCashoutLogically(userId, index, targetMultiplier);
        }
    }

    // [NEW] Called by GameEngineService in the main loop

    private synchronized CashOutResult executeCashoutLogically(String userId, int index, double multiplier) {
        // The actual logic that was previously in cashOut
        // ... (Reusing existing logic logic)

        // Need to duplicate logic or refactor logic below...
        // Let's copy the core logic here and make cashOut call this for AutoCashout.

        String betKey = getBetKey(userId, index);
        Bet bet = currentRoundBets.get(betKey);

        if (bet == null || bet.getCashOutMultiplier() > 0)
            return null; // Already cashed out or bet not found

        double winAmount = round(bet.getAmount() * multiplier);

        bet.setCashOutMultiplier(multiplier);
        bet.setProfit(round(winAmount - bet.getAmount()));

        String txId = java.util.UUID.randomUUID().toString();
        boolean success = walletService.creditWinnings(userId, winAmount, gameEngine.getCurrentGame().getId(), txId);

        if (!success) {
            LOG.error("CRITICAL: Failed to credit winnings for user " + userId);
        }

        double newBalance = walletService.getBalance(userId);

        LOG.info("CASHOUT " + userId + " [" + index + "] vince " + winAmount + "€ (" + multiplier
                + "x). Nuovo saldo: " + newBalance);

        gameEngine.broadcast("CASHOUT:" + userId + ":" + multiplier + ":" + winAmount + ":" + index);
        return new CashOutResult(winAmount, newBalance, multiplier);
    }

    public void checkAutoCashouts(double currentMultiplier) {
        currentRoundBets.values().forEach(bet -> {
            if (bet.getCashOutMultiplier() == 0 && bet.getAutoCashout() > 1.00
                    && currentMultiplier >= bet.getAutoCashout()) {
                try {
                    // Passa l'index e il valore ESATTO dell'auto-cashout come target
                    cashOut(bet.getUserId(), bet.getIndex(), bet.getAutoCashout());
                    LOG.info("AUTO-CASHOUT per " + bet.getUsername() + " [" + bet.getIndex() + "] a "
                            + bet.getAutoCashout() + "x (preciso)");
                } catch (Exception e) {
                    LOG.error("Errore autocashout " + bet.getUsername(), e);
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

        currentRoundBets.remove(betKey);

        String txId = java.util.UUID.randomUUID().toString();
        walletService.refundBet(userId, bet.getAmount(), game.getId(), txId);

        LOG.info("Scommessa cancellata " + userId + " [" + index + "]. Rimborso: " + bet.getAmount());
        gameEngine.broadcast("CANCEL_BET:" + userId + ":" + index);
    }

    public List<Bet> resetBetsForNewRound() {
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
