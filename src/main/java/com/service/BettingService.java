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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.time.LocalDate;

@ApplicationScoped
public class BettingService {

    private static final Logger LOG = Logger.getLogger(BettingService.class);
    private final Map<String, Bet> currentRoundBets = new ConcurrentHashMap<>();
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
            // Salva nonce per 5 minuti (tempo sufficiente per un round)
            valueCommands.setex(nonceKey, 300, userId);
        }

        if (game == null || game.getStatus() != GameState.WAITING) {
            throw new IllegalStateException(
                    "Non puoi scommettere ora. Il gioco è " + (game != null ? game.getStatus() : "null"));
        }
        if (amount < 0.10) {
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
            String txId = "bet:" + game.getId() + ":" + userId + ":" + index;

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

        bet.setProfit(round(winAmount - bet.getAmount()));
        getGameEngine().broadcast("CASHOUT:" + userId + ":" + multiplier + ":" + winAmount + ":" + index);

        String txId = "win:" + bet.getGameId() + ":" + userId + ":" + index;
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
            String compactData = String.join("|",
                    bet.getUserId(),
                    bet.getUsername(),
                    String.valueOf(bet.getAmount()),
                    String.valueOf(bet.getProfit()),
                    String.valueOf(bet.getCashOutMultiplier()),
                    String.valueOf(System.currentTimeMillis()));

            zsetCommands.zadd(profitKey, bet.getProfit(), compactData);
            keyCommands.expire(profitKey, 172800);

            zsetCommands.zadd(multiKey, bet.getCashOutMultiplier(), compactData);
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
                Map<String, Object> map = new HashMap<>();
                if (dataString.contains("|")) {
                    String[] parts = dataString.split("\\|");
                    if (parts.length >= 6) {
                        map.put("id", parts[0] + "_" + parts[5]);
                        map.put("userId", parts[0]);
                        map.put("username", parts[1]);
                        map.put("betAmount", Double.parseDouble(parts[2]));
                        map.put("profit", Double.parseDouble(parts[3]));
                        map.put("multiplier", Double.parseDouble(parts[4]));
                        map.put("timestamp", Long.parseLong(parts[5]));
                        map.put("avatarUrl", "/users/" + parts[0] + "/avatar");
                        return map;
                    }
                }
                return objectMapper.readValue(dataString, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                return null;
            }
        }).filter(item -> item != null).collect(Collectors.toList());
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

        currentRoundBets.remove(betKey);

        String txId = "refund:" + bet.getGameId() + ":" + userId + ":" + index;
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
