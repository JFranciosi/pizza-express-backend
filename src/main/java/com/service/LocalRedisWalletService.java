package com.service;

import com.model.Player;
import com.repository.PlayerRepository;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;

@ApplicationScoped
public class LocalRedisWalletService implements WalletService {

    private static final Logger LOG = Logger.getLogger(LocalRedisWalletService.class);
    private static final String IDEMPOTENCY_PREFIX = "wallet:tx:";
    // TTL per le chiavi di idempotenza (es. 24h)
    private static final Duration PROCESSED_TX_TTL = Duration.ofHours(24);

    @Inject
    PlayerRepository playerRepository;

    private final ReactiveValueCommands<String, String> valueCommands;

    @Inject
    public LocalRedisWalletService(ReactiveRedisDataSource ds) {
        this.valueCommands = ds.value(String.class);
    }

    @Override
    public boolean reserveFunds(String userId, double amount, String roundId, String transactionId) {
        if (isTransactionProcessed(transactionId)) {
            LOG.warn("Transazione " + transactionId + " già processata (Idempotency Hit)");
            return true; // Già fatto, consideriamo successo
        }

        // Lock utente per evitare race condition sul saldo
        synchronized (getUserLock(userId)) {
            Player player = playerRepository.findById(userId);
            if (player == null) {
                LOG.error("Utente non trovato per reserveFunds: " + userId);
                return false;
            }

            if (player.getBalance() < amount) {
                return false;
            }

            double newBalance = round(player.getBalance() - amount);
            player.setBalance(newBalance);
            playerRepository.save(player);

            if (player.getBalance() < 0.10) {
                playerRepository.markZeroBalance(userId);
            }

            markTransactionAsProcessed(transactionId);
            LOG.info("Fondi riservati: " + amount + " per user " + userId + ". TX: " + transactionId);
            return true;
        }
    }

    @Override
    public boolean creditWinnings(String userId, double amount, String roundId, String transactionId) {
        if (isTransactionProcessed(transactionId)) {
            LOG.warn("Transazione " + transactionId + " già processata (Idempotency Hit)");
            return true;
        }

        synchronized (getUserLock(userId)) {
            Player player = playerRepository.findById(userId);
            if (player == null) {
                return false;
            }

            double newBalance = round(player.getBalance() + amount);
            player.setBalance(newBalance);
            playerRepository.save(player);

            if (player.getBalance() > 0) {
                playerRepository.clearZeroBalance(userId);
            }

            markTransactionAsProcessed(transactionId);
            LOG.info("Vincita accreditata: " + amount + " per user " + userId + ". TX: " + transactionId);
            return true;
        }
    }

    @Override
    public boolean refundBet(String userId, double amount, String roundId, String transactionId) {
        if (isTransactionProcessed(transactionId)) {
            return true; // Già rimborsato
        }

        synchronized (getUserLock(userId)) {
            Player player = playerRepository.findById(userId);
            if (player == null)
                return false;

            double newBalance = round(player.getBalance() + amount);
            player.setBalance(newBalance);
            playerRepository.save(player);

            if (player.getBalance() > 0) {
                playerRepository.clearZeroBalance(userId);
            }

            markTransactionAsProcessed(transactionId);
            LOG.info("Rimborso effettuato: " + amount + " per user " + userId + ". TX: " + transactionId);
            return true;
        }
    }

    @Override
    public double getBalance(String userId) {
        Player player = playerRepository.findById(userId);
        return player != null ? player.getBalance() : 0.0;
    }

    // --- Helpers ---

    private boolean isTransactionProcessed(String transactionId) {
        // Controlliamo se la chiave esiste su Redis
        // valueCommands restituisce un Uni, quindi blocchiamo per l'implementazione
        // sincrona attuale
        // In futuro si potrebbe fare tutto reattivo
        return valueCommands.get(IDEMPOTENCY_PREFIX + transactionId).await().indefinitely() != null;
    }

    private void markTransactionAsProcessed(String transactionId) {
        valueCommands.set(IDEMPOTENCY_PREFIX + transactionId, "PROCESSED", new SetArgs().ex(PROCESSED_TX_TTL))
                .await().indefinitely();
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // Cache lock primitiva per evitare locking distribuito complesso per ora
    // Nota: Funziona solo se c'è 1 istanza del backend. Per scaling orizzontale
    // serve Redisson lock.
    private final java.util.concurrent.ConcurrentHashMap<String, Object> userLocks = new java.util.concurrent.ConcurrentHashMap<>();

    private Object getUserLock(String userId) {
        return userLocks.computeIfAbsent(userId, k -> new Object());
    }
}
