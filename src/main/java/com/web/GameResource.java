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

@Path("/game")
@Produces(MediaType.APPLICATION_JSON)
public class GameResource {

    @Inject
    GameEngineService gameEngine;

    @GET
    @Path("/history")
    @PermitAll
    public Uni<List<String>> getFullHistory(@jakarta.ws.rs.QueryParam("limit") Integer limit) {
        int actualLimit = (limit != null && limit > 0) ? limit : 50; // Default 50 if not specified
        return gameEngine.getFullHistory(actualLimit);
    }
}
