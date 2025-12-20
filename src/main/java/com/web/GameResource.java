package com.web;

import com.service.GameEngineService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import com.repository.PlayerRepository;
import com.model.Player;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;

@Path("/game")
@Produces(MediaType.APPLICATION_JSON)
public class GameResource {

    @Inject
    GameEngineService gameEngine;

    @Inject
    PlayerRepository playerRepository;

    @GET
    @Path("/history")
    @PermitAll
    public Uni<List<String>> getFullHistory(@jakarta.ws.rs.QueryParam("limit") Integer limit) {
        int actualLimit = (limit != null && limit > 0) ? limit : 50; // Default 50 if not specified
        return gameEngine.getFullHistory(actualLimit);
    }

    @POST
    @Path("/debug/force-zero/{userId}")
    @PermitAll
    public String forceZeroBalance(@PathParam("userId") String userId) {
        Player player = playerRepository.findById(userId);
        if (player == null)
            return "User not found: " + userId;
        player.setBalance(0.0);
        playerRepository.save(player);
        playerRepository.markZeroBalance(userId);
        return "DEBUG: User " + userId + " set to 0.0 and marked for refill in 60s";
    }

    @POST
    @Path("/debug/check-all-balances")
    @PermitAll
    public String checkAllBalances() {
        return playerRepository.scanAndFixZeroBalances();
    }
}
