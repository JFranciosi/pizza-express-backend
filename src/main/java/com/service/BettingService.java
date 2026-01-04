package com.service;

import com.dto.CashOutResult;
import com.model.Bet;
import com.model.Game;
import com.model.GameState;
import com.model.Player;
import com.repository.PlayerRepository;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.sortedset.SortedSetCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.sortedset.ZRangeArgs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.time.LocalDate;

@ApplicationScoped
public class BettingService {

    private static final Logger LOG = Logger.getLogger(BettingService.class);
    private final Map<String, Bet> currentRoundBets = new ConcurrentHashMap<>();
    private final ConcurrentSkipListMap<Double, List<String>> autoCashoutMap = new ConcurrentSkipListMap<>();
    private final SortedSetCommands<String, String> zsetCommands;
    private final KeyCommands<String> keyCommands;
    private final io.quarkus.redis.datasource.value.ValueCommands<String, String> valueCommands;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final PlayerRepository playerRepository;
    private final Instance<GameEngineService> gameEngineInstance;
    private final WalletService walletService;

    @Inject
    public BettingService(RedisDataSource ds,
            PlayerRepository playerRepository,
            Instance<GameEngineService> gameEngineInstance,
            WalletService walletService) {
        this.zsetCommands = ds.sortedSet(String.class);
        this.keyCommands = ds.key(String.class);
        this.valueCommands = ds.value(String.class);
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

    public void placeBet(String userId, String username, double amount, double autoCashout, int index, String nonce) {
        Game game = getGameEngine().getCurrentGame();

        if (nonce != null && !nonce.isEmpty()) {
            String nonceKey = "bet:nonce:" + nonce;
            String existing = valueCommands.get(nonceKey);
            if (existing != null) {
                LOG.warn("Replay attack detected! Nonce: " + nonce + " User: " + userId);
                throw new IllegalStateException("Duplicate bet (Replay detected).");
            }
            valueCommands.setex(nonceKey, 300, userId);
        }

        if (game == null || game.getStatus() != GameState.WAITING) {
            throw new IllegalStateException("Non puoi scommettere ora.");
        }
        if (amount < 0.10 || amount > 100) {
            throw new IllegalArgumentException("Importo non valido (0.10 - 100€)");
        }

        String betKey = getBetKey(userId, index);

        currentRoundBets.compute(betKey, (key, existingBet) -> {
            if (existingBet != null) {
                throw new IllegalStateException("Scommessa già presente.");
            }

            double finalAmount = round(amount);
            String txId = "bet:" + game.getId() + ":" + userId + ":" + index;

            boolean success = walletService.reserveFunds(userId, finalAmount, game.getId(), txId);
            if (!success) {
                throw new IllegalStateException("Saldo insufficiente.");
            }

            Player player = playerRepository.findById(userId);
            String avatarUrl = (player != null) ? player.getAvatarUrl() : null;

            Bet bet = new Bet(userId, username, game.getId(), finalAmount, index, avatarUrl);
            bet.setAutoCashout(autoCashout);
            return bet;
        });

        if (autoCashout > 1.00) {
            autoCashoutMap.computeIfAbsent(autoCashout, k -> new CopyOnWriteArrayList<>()).add(betKey);
        }

        Bet bet = currentRoundBets.get(betKey);
        String avatarApiUrl = (bet.getAvatarUrl() != null && !bet.getAvatarUrl().isEmpty())
                ? "/users/" + userId + "/avatar"
                : "";

        LOG.info("Scommessa piazzata: " + username + " [" + index + "] - " + amount + "€ (Auto: " + autoCashout + "x)");
        getGameEngine().broadcast("BET:" + userId + ":" + username + ":" + amount + ":" + index + ":" + avatarApiUrl);
    }

    public CashOutResult cashOut(String userId, int index) {
        return cashOut(userId, index, null);
    }

    public CashOutResult cashOut(String userId, int index, Double targetMultiplier) {
        Game game = getGameEngine().getCurrentGame();
        if (game == null || (game.getStatus() != GameState.FLYING && targetMultiplier == null)) {
            throw new IllegalStateException("Gioco non attivo.");
        }
        return executeCashoutLogically(userId, index,
                targetMultiplier != null ? targetMultiplier : game.getMultiplier());
    }

    private synchronized CashOutResult executeCashoutLogically(String userId, int index, double multiplier) {
        String betKey = getBetKey(userId, index);
        Bet bet = currentRoundBets.get(betKey);

        if (bet == null || bet.getCashOutMultiplier() > 0)
            return null;

        double winAmount = round(bet.getAmount() * multiplier);

        bet.setCashOutMultiplier(multiplier);
        bet.setProfit(round(winAmount - bet.getAmount()));
        if (bet.getAutoCashout() > 1.0) {
            List<String> keys = autoCashoutMap.get(bet.getAutoCashout());
            if (keys != null) {
                keys.remove(betKey);
            }
        }

        getGameEngine().broadcast("CASHOUT:" + userId + ":" + multiplier + ":" + winAmount + ":" + index);

        String txId = "win:" + bet.getGameId() + ":" + userId + ":" + index;
        boolean success = walletService.creditWinnings(userId, winAmount, getGameEngine().getCurrentGame().getId(),
                txId);

        if (!success) {
            LOG.error("CRITICAL: Errore accredito vincita manuale " + userId);
        }

        double newBalance = walletService.getBalance(userId);
        saveToLeaderboard(bet);

        LOG.info("CASHOUT " + userId + " [" + index + "] vince " + winAmount + "€ (" + multiplier
                + "x). Nuovo saldo: " + newBalance);

        return new CashOutResult(winAmount, newBalance, multiplier);
    }

    private void saveToLeaderboard(Bet bet) {
        String today = LocalDate.now().toString();
        String profitKey = "leaderboard:profit:" + today;
        String multiKey = "leaderboard:multiplier:" + today;

        try {
            Map<String, Object> data = new HashMap<>();
            data.put("userId", bet.getUserId());
            data.put("username", bet.getUsername());
            data.put("betAmount", bet.getAmount());
            data.put("profit", bet.getProfit());
            data.put("multiplier", bet.getCashOutMultiplier());
            data.put("timestamp", System.currentTimeMillis());
            data.put("avatarUrl", "/users/" + bet.getUserId() + "/avatar");

            String json = objectMapper.writeValueAsString(data);

            zsetCommands.zadd(profitKey, bet.getProfit(), json);
            keyCommands.expire(profitKey, 172800);

            zsetCommands.zadd(multiKey, bet.getCashOutMultiplier(), json);
            keyCommands.expire(multiKey, 172800);
        } catch (Exception e) {
            LOG.error("Error saving to leaderboard", e);
        }
    }

    public List<Map<String, Object>> getTopBets(String type) {
        String today = LocalDate.now().toString();
        String key = "leaderboard:" + (type.equals("multiplier") ? "multiplier" : "profit") + ":" + today;

        List<String> list = zsetCommands.zrange(key, 0L, 9L, new ZRangeArgs().rev());

        return list.stream().map(dataString -> {
            try {
                Map<String, Object> result = null;
                if (dataString.contains("|")) {
                    String[] parts = dataString.split("\\|");
                    if (parts.length >= 6) {
                        result = new HashMap<>();
                        result.put("id", parts[0] + "_" + parts[5]);
                        // Internal ID removed for privacy
                        // result.put("userId", parts[0]);
                        result.put("username", parts[1]);
                        result.put("betAmount", Double.parseDouble(parts[2]));
                        result.put("profit", Double.parseDouble(parts[3]));
                        result.put("multiplier", Double.parseDouble(parts[4]));
                        result.put("timestamp", Long.parseLong(parts[5]));
                        result.put("avatarUrl", "/users/" + parts[0] + "/avatar");
                    }
                } else {
                    result = objectMapper.readValue(dataString, new TypeReference<Map<String, Object>>() {
                    });
                }

                if (result != null) {
                    result.remove("userId");
                }
                return result;
            } catch (Exception e) {
                return null;
            }
        }).filter(item -> item != null).collect(Collectors.toList());
    }

    public void checkAutoCashouts(double currentMultiplier) {
        var eligibleMap = autoCashoutMap.headMap(currentMultiplier, true);

        if (eligibleMap.isEmpty()) {
            return;
        }

        for (Map.Entry<Double, List<String>> entry : eligibleMap.entrySet()) {
            double targetMultiplier = entry.getKey();
            List<String> betKeys = entry.getValue();

            for (String betKey : betKeys) {
                Bet bet = currentRoundBets.get(betKey);
                if (bet != null && bet.getCashOutMultiplier() == 0) {
                    try {
                        cashOut(bet.getUserId(), bet.getIndex(), targetMultiplier);
                        LOG.info("AUTO-CASHOUT RAPIDO: " + bet.getUsername() + " a " + targetMultiplier + "x");
                    } catch (Exception e) {
                        LOG.error("Errore autocashout ottimizzato " + betKey, e);
                    }
                }
            }
        }
        eligibleMap.clear();
    }

    public void cancelBet(String userId, int index) {
        Game game = getGameEngine().getCurrentGame();
        if (game == null || game.getStatus() != GameState.WAITING) {
            throw new IllegalStateException("Troppo tardi.");
        }

        String betKey = getBetKey(userId, index);
        Bet bet = currentRoundBets.remove(betKey);

        if (bet == null)
            throw new IllegalStateException("Nessuna scommessa.");

        if (bet.getAutoCashout() > 1.0) {
            List<String> keys = autoCashoutMap.get(bet.getAutoCashout());
            if (keys != null) {
                keys.remove(betKey);
                if (keys.isEmpty()) {
                    autoCashoutMap.remove(bet.getAutoCashout());
                }
            }
        }

        String txId = "refund:" + bet.getGameId() + ":" + userId + ":" + index;
        walletService.refundBet(userId, bet.getAmount(), game.getId(), txId);

        getGameEngine().broadcast("CANCEL_BET:" + userId + ":" + index);
    }

    public List<Bet> resetBetsForNewRound() {
        List<Bet> oldBets = new ArrayList<>(currentRoundBets.values());
        currentRoundBets.clear();
        autoCashoutMap.clear();
        return oldBets;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public Map<String, Bet> getCurrentBets() {
        return currentRoundBets;
    }
}
