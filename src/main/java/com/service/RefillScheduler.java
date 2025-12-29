package com.service;

import com.model.Player;
import com.repository.PlayerRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class RefillScheduler {

    private static final Logger LOG = Logger.getLogger(RefillScheduler.class);

    private static final long REFILL_DELAY_MS = 24 * 60 * 60 * 1000L;

    private final PlayerRepository playerRepository;

    @Inject
    public RefillScheduler(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    @Scheduled(every = "5m")
    @io.smallrye.common.annotation.RunOnVirtualThread
    void processRefills() {
        LOG.info("Running Auto-Refill check...");
        long now = System.currentTimeMillis();
        long cutoffTime = now - REFILL_DELAY_MS;

        List<String> eligiblePlayerIds = playerRepository.findEligibleForRefill(cutoffTime);

        if (eligiblePlayerIds.isEmpty()) {
            LOG.info("No players eligible for refill.");
            return;
        }

        LOG.info("Found " + eligiblePlayerIds.size() + " players eligible for refill.");

        for (String playerId : eligiblePlayerIds) {
            try {
                Player player = playerRepository.findById(playerId);
                if (player != null) {
                    if (player.getBalance() <= 0) {
                        player.setBalance(500.0);
                        playerRepository.save(player);
                        LOG.info("REFILLED bucket for user: " + player.getUsername());
                    }
                    playerRepository.clearZeroBalance(playerId);
                } else {
                    playerRepository.clearZeroBalance(playerId);
                }
            } catch (Exception e) {
                LOG.error("Error processing refill for player: " + playerId, e);
            }
        }
    }
}
