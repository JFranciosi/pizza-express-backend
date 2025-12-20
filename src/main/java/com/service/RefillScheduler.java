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
    // 24 Hours in milliseconds
    private static final long REFILL_DELAY_MS = 24 * 60 * 60 * 1000L;

    @Inject
    PlayerRepository playerRepository;

    @Scheduled(every = "1h")
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
                    // Remove from tracking list (either refilled or didn't need it)
                    playerRepository.clearZeroBalance(playerId);
                } else {
                    // Player no longer exists, clean up
                    playerRepository.clearZeroBalance(playerId);
                }
            } catch (Exception e) {
                LOG.error("Error processing refill for player: " + playerId, e);
            }
        }
    }
}
