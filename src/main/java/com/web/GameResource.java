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
import java.util.List;

@Path("/game")
@Produces(MediaType.APPLICATION_JSON)
public class GameResource {

    private final GameEngineService gameEngine;

    @Inject
    public GameResource(GameEngineService gameEngine) {
        this.gameEngine = gameEngine;
    }

    @GET
    @Path("/history")
    @PermitAll
    @RunOnVirtualThread
    public List<String> getFullHistory(@QueryParam("limit") Integer limit) {
        int actualLimit = (limit != null && limit > 0) ? limit : 50;
        return gameEngine.getFullHistory(actualLimit);
    }
}
