package com.web;

import com.dto.CashOutResult;
import com.service.BettingService;
import com.service.TokenService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/bet")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class BettingResource {

    private final BettingService bettingService;
    private final TokenService tokenService;

    @Inject
    public BettingResource(BettingService bettingService, TokenService tokenService) {
        this.bettingService = bettingService;
        this.tokenService = tokenService;
    }

    public static class BetRequest {
        public double amount;
        public double autoCashout;
        public int index;
    }

    @POST
    @Path("/place")
    public Response placeBet(@HeaderParam("Authorization") String token, BetRequest req) {
        try {
            String userId = tokenService.getUserIdFromToken(token);
            String username = tokenService.getUsernameFromToken(token);
            int betIndex = (req.index == 1) ? 1 : 0;
            bettingService.placeBet(userId, username, req.amount, req.autoCashout, betIndex);
            return Response.ok().build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            return Response.serverError().entity(new ErrorResponse("Errore interno")).build();
        }
    }

    @POST
    @Path("/cashout")
    public Response cashOut(@HeaderParam("Authorization") String token, @QueryParam("index") Integer index) {
        try {
            String userId = tokenService.getUserIdFromToken(token);
            int betIndex = (index != null && index == 1) ? 1 : 0;
            CashOutResult result = bettingService.cashOut(userId, betIndex);
            return Response.ok(result).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            return Response.serverError().entity(new ErrorResponse("Errore interno")).build();
        }
    }

    @POST
    @Path("/cancel")
    public Response cancelBet(@HeaderParam("Authorization") String token, @QueryParam("index") Integer index) {
        try {
            String userId = tokenService.getUserIdFromToken(token);
            int betIndex = (index != null && index == 1) ? 1 : 0;
            bettingService.cancelBet(userId, betIndex);
            return Response.ok().build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            return Response.serverError().entity(new ErrorResponse("Errore interno")).build();
        }
    }

    @GET
    @Path("/top")
    @io.smallrye.common.annotation.RunOnVirtualThread
    public Response getTopBets(@QueryParam("type") String type) {
        if (type == null || (!type.equals("profit") && !type.equals("multiplier"))) {
            type = "profit";
        }
        try {
            return Response.ok(bettingService.getTopBets(type)).build();
        } catch (Exception e) {
            return Response.serverError().entity(new ErrorResponse("Error fetching top bets")).build();
        }
    }

    public static class ErrorResponse {
        public String error;

        public ErrorResponse(String error) {
            this.error = error;
        }
    }
}
