package com.web;

import com.dto.CashOutResult;
import com.web.model.ErrorResponse;
import com.service.BettingService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import io.quarkus.security.Authenticated;

@Path("/bet")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class BettingResource {

    private final BettingService bettingService;
    private final JsonWebToken jwt;

    @Inject
    public BettingResource(BettingService bettingService, JsonWebToken jwt) {
        this.bettingService = bettingService;
        this.jwt = jwt;
    }

    public static class BetRequest {
        public double amount;
        public double autoCashout;
        public int index;
        public String nonce;
    }

    @POST
    @Path("/place")
    @Authenticated
    public Response placeBet(BetRequest req) {
        try {
            String userId = jwt.getClaim("userId");
            String username = jwt.getClaim("username");

            if (username == null) {
                username = jwt.getName();
            }

            int betIndex = (req.index == 1) ? 1 : 0;
            bettingService.placeBet(userId, username, req.amount, req.autoCashout, betIndex, req.nonce);
            return Response.ok().build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError().entity(new ErrorResponse("Errore interno: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/cashout")
    @Authenticated
    public Response cashOut(@QueryParam("index") Integer index) {
        try {
            String userId = jwt.getClaim("userId");
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
    @Authenticated
    public Response cancelBet(@QueryParam("index") Integer index) {
        try {
            String userId = jwt.getClaim("userId");
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
    @RunOnVirtualThread
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

}
