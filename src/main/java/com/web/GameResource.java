package com.web;

import com.service.GameEngineService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/game")
@Produces(MediaType.APPLICATION_JSON)
public class GameResource {

    private final GameEngineService gameEngine;
    private final com.service.ProvablyFairService pfService;

    @Inject
    public GameResource(GameEngineService gameEngine, com.service.ProvablyFairService pfService) {
        this.gameEngine = gameEngine;
        this.pfService = pfService;
    }

    @GET
    @Path("/history")
    @PermitAll
    @RunOnVirtualThread
    public List<String> getFullHistory(@QueryParam("limit") Integer limit) {
        int actualLimit = (limit != null && limit > 0) ? limit : 50;
        return gameEngine.getFullHistory(actualLimit);
    }

    @GET
    @Path("/fairness")
    @RunOnVirtualThread
    public Response getFairnessDetails() {
        String commitment = pfService.getCurrentCommitment();
        long remaining = pfService.getRemainingGames();

        if (commitment == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("Chain generation in progress").build();
        }

        return Response.ok(new FairnessDto(commitment, remaining)).build();
    }

    public record FairnessDto(String activeCommitment, long remainingGames) {
    }
}
