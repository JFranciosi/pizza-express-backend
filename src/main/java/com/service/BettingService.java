package com.service;

import com.dto.CashOutResult;
import com.model.Bet;
import com.model.Game;
import com.model.GameState;
import com.model.Player;
import com.repository.PlayerRepository;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.sortedset.ReactiveSortedSetCommands;
import io.quarkus.redis.datasource.sortedset.ZRangeArgs;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.time.LocalDate;

@ApplicationScoped
public class BettingService {

    private static final Logger LOG = Logger.getLogger(BettingService.class);
    private final Map<String, Bet> currentRoundBets = new ConcurrentHashMap<>();
    private final ReactiveSortedSetCommands<String, String> zsetCommands;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final PlayerRepository playerRepository;
    private final Instance<GameEngineService> gameEngineInstance;
    private final WalletService walletService;

    @Inject
    public BettingService(ReactiveRedisDataSource ds,
            PlayerRepository playerRepository,
            Instance<GameEngineService> gameEngineInstance,
            WalletService walletService) {
        this.zsetCommands = ds.sortedSet(String.class);
        this.playerRepository = playerRepository;
        this.gameEngineInstance = gameEngineInstance;
        this.walletService = walletService;
    }

    private GameEngineService getGameEngine() {
        return gameEngineInstance.get();
    }

    private String getBetKey(String userId, int index) {
        return userId + ":" + index;
    }

    public void placeBet(String userId, String username, double amount, double autoCashout, int index) {
        Game game = getGameEngine().getCurrentGame();

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
        if (index < 0 || index > 1) {
            throw new IllegalArgumentException("Index scommessa non valido (0-1)");
        }

        String betKey = getBetKey(userId, index);

        currentRoundBets.compute(betKey, (key, existingBet) -> {
            if (existingBet != null) {
                throw new IllegalStateException("Hai già piazzato questa scommessa (" + index + ")");
            }

            double finalAmount = round(amount);
            String txId = UUID.randomUUID().toString();

            boolean success = walletService.reserveFunds(userId, finalAmount, game.getId(), txId);
            if (!success) {
                throw new IllegalStateException("Saldo insufficiente o errore transazione");
            }
            Player player = playerRepository.findById(userId);
            String avatarUrl = (player != null) ? player.getAvatarUrl() : null;

            Bet bet = new Bet(userId, username, game.getId(), finalAmount, index, avatarUrl);
            bet.setAutoCashout(autoCashout);
            return bet;
        });

        String avatarUrl = currentRoundBets.get(betKey).getAvatarUrl();
        String avatarApiUrl = (avatarUrl != null && !avatarUrl.isEmpty()) ? "/users/" + userId + "/avatar" : "";

        LOG.info("Scommessa piazzata: " + username + " [" + index + "] - " + amount + "€");
        getGameEngine().broadcast("BET:" + userId + ":" + username + ":" + amount + ":" + index + ":" + avatarApiUrl);
    }

    public CashOutResult cashOut(String userId, int index) {
        return cashOut(userId, index, null);
    }

    public CashOutResult cashOut(String userId, int index, Double targetMultiplier) {
        Game game = getGameEngine().getCurrentGame();
        if (game == null) {
            throw new IllegalStateException("Impossibile fare cashout. Gioco non attivo.");
        }

        if (game.getStatus() != GameState.FLYING && targetMultiplier == null) {
            throw new IllegalStateException("Impossibile fare cashout. Gioco non attivo.");
        }

        String betKey = getBetKey(userId, index);
        Bet bet = currentRoundBets.get(betKey);

        if (bet == null)
            throw new IllegalStateException("Nessuna scommessa attiva trovata (Index " + index + ")");
        if (bet.getCashOutMultiplier() > 0)
            throw new IllegalStateException("Hai già incassato questa scommessa!");
        if (targetMultiplier == null) {
            return executeCashoutLogically(userId, index, game.getMultiplier());
        } else {
            return executeCashoutLogically(userId, index, targetMultiplier);
        }
    }

    private synchronized CashOutResult executeCashoutLogically(String userId, int index, double multiplier) {

        String betKey = getBetKey(userId, index);
        Bet bet = currentRoundBets.get(betKey);

        if (bet == null || bet.getCashOutMultiplier() > 0)
            return null;

        double winAmount = round(bet.getAmount() * multiplier);

        bet.setCashOutMultiplier(multiplier);
        bet.setProfit(round(winAmount - bet.getAmount()));
        getGameEngine().broadcast("CASHOUT:" + userId + ":" + multiplier + ":" + winAmount + ":" + index);

        String txId = UUID.randomUUID().toString();
        boolean success = walletService.creditWinnings(userId, winAmount, getGameEngine().getCurrentGame().getId(),
                txId);

        if (!success) {
            LOG.error("CRITICAL: Failed to credit winnings for user " + userId);
        }

        double newBalance = walletService.getBalance(userId);

        LOG.info("CASHOUT " + userId + " [" + index + "] vince " + winAmount + "€ (" + multiplier
                + "x). Nuovo saldo: " + newBalance);
        saveToLeaderboard(bet);

        return new CashOutResult(winAmount, newBalance, multiplier);
    }

    private void saveToLeaderboard(Bet bet) {
        String today = LocalDate.now().toString();
        String profitKey = "leaderboard:profit:" + today;
        String multiKey = "leaderboard:multiplier:" + today;

        try {
            Map<String, Object> data = new HashMap<>();
            data.put("id", bet.getUserId() + "_" + System.currentTimeMillis()); // Unique ID for ZSet member uniqueness
            data.put("username", bet.getUsername());
            data.put("avatarUrl", bet.getAvatarUrl());
            data.put("betAmount", bet.getAmount());
            data.put("profit", bet.getProfit());
            data.put("multiplier", bet.getCashOutMultiplier());
            data.put("timestamp", System.currentTimeMillis());

            String json = objectMapper.writeValueAsString(data);
            zsetCommands.zadd(profitKey, bet.getProfit(), json).subscribe().with(v -> {
            }, t -> LOG.error("Redis Profit Error", t));
            zsetCommands.zadd(multiKey, bet.getCashOutMultiplier(), json).subscribe().with(v -> {
            }, t -> LOG.error("Redis Multi Error", t));
        } catch (Exception e) {
            LOG.error("Error saving to leaderboard", e);
        }
    }

    public Uni<List<Map<String, Object>>> getTopBets(String type) {
        String today = LocalDate.now().toString();
        String key = "leaderboard:" + (type.equals("multiplier") ? "multiplier" : "profit") + ":" + today;

        return zsetCommands.zrange(key, 0L, 9L, new ZRangeArgs().rev())
                .map(list -> list.stream().map(json -> {
                    try {
                        return objectMapper.readValue(json,
                                new TypeReference<Map<String, Object>>() {
                                });
                    } catch (Exception e) {
                        return null;
                    }
                }).filter(item -> item != null).collect(Collectors.toList()));
    }

    public void checkAutoCashouts(double currentMultiplier) {
        currentRoundBets.values().forEach(bet -> {
            if (bet.getCashOutMultiplier() == 0 && bet.getAutoCashout() > 1.00
                    && currentMultiplier >= bet.getAutoCashout()) {
                try {
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
        Game game = getGameEngine().getCurrentGame();
        if (game == null || game.getStatus() != GameState.WAITING) {
            throw new IllegalStateException("Non puoi cancellare la scommessa ora.");
        }

        String betKey = getBetKey(userId, index);
        Bet bet = currentRoundBets.get(betKey);

        if (bet == null)
            throw new IllegalStateException("Nessuna scommessa da cancellare (Index " + index + ")");

        currentRoundBets.remove(betKey);
        // Duplicate remove call removed

        String txId = UUID.randomUUID().toString();
        walletService.refundBet(userId, bet.getAmount(), game.getId(), txId);

        LOG.info("Scommessa cancellata " + userId + " [" + index + "]. Rimborso: " + bet.getAmount());
        getGameEngine().broadcast("CANCEL_BET:" + userId + ":" + index);
    }

    public List<Bet> resetBetsForNewRound() {
        List<Bet> oldBets = currentRoundBets.values().stream().collect(Collectors.toList());
        currentRoundBets.clear();
        return oldBets;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public Map<String, Bet> getCurrentBets() {
        return currentRoundBets;
    }
}
