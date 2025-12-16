package com.web;

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

    @Inject
    BettingService bettingService;

    @Inject
    TokenService tokenService;

    public static class BetRequest {
        public double amount;
        public double autoCashout;
    }

    @POST
    @Path("/place")
    public Response placeBet(@HeaderParam("Authorization") String token, BetRequest req) {
        try {
            String userId = tokenService.getUserIdFromToken(token);
            String username = tokenService.getUsernameFromToken(token);
            bettingService.placeBet(userId, username, req.amount, req.autoCashout);
            return Response.ok().build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            return Response.serverError().entity(new ErrorResponse("Errore interno")).build();
        }
    }

    @POST
    @Path("/cashout")
    public Response cashOut(@HeaderParam("Authorization") String token) {
        try {
            String userId = tokenService.getUserIdFromToken(token);
            BettingService.CashOutResult result = bettingService.cashOut(userId);
            return Response.ok(result).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            return Response.serverError().entity(new ErrorResponse("Errore interno")).build();
        }
    }

    @POST
    @Path("/cancel")
    public Response cancelBet(@HeaderParam("Authorization") String token) {
        try {
            String userId = tokenService.getUserIdFromToken(token);
            bettingService.cancelBet(userId);
            return Response.ok().build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            return Response.serverError().entity(new ErrorResponse("Errore interno")).build();
        }
    }

    public static class ErrorResponse {
        public String error;

        public ErrorResponse(String error) {
            this.error = error;
        }
    }
}
