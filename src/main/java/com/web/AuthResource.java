package com.web;

import com.service.AuthService;
import com.web.model.LoginRequest;
import com.web.model.RegisterRequest;
import com.web.model.RefreshRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    public static class ChangePasswordRequest {
        public String userId; // Optional if passed via token, but frontend sends body
        public String oldPass;
        public String newPass;
    }

    public static class UpdateProfileRequest {
        public String email;
        public String password;
    }

    @Inject
    AuthService authService;

    @Inject
    com.service.TokenService tokenService;

    @POST
    @Path("/change-password")
    public Response changePassword(@jakarta.ws.rs.HeaderParam("Authorization") String token,
            ChangePasswordRequest req) {
        try {
            String userId = tokenService.getUserIdFromToken(token);
            authService.changePassword(userId, req.oldPass, req.newPass);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new com.web.BettingResource.ErrorResponse(e.getMessage())).build();
        }
    }

    @POST
    @Path("/update-profile")
    public Response updateProfile(@jakarta.ws.rs.HeaderParam("Authorization") String token,
            UpdateProfileRequest req) {
        try {
            String userId = tokenService.getUserIdFromToken(token);
            authService.updateEmail(userId, req.email, req.password);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new com.web.BettingResource.ErrorResponse(e.getMessage())).build();
        }
    }

    @POST
    @Path("/register")
    @PermitAll
    public Response register(RegisterRequest req) {
        return Response.ok(authService.register(req)).build();
    }

    @POST
    @Path("/login")
    @PermitAll
    public Response login(LoginRequest req) {
        return Response.ok(authService.login(req)).build();
    }

    @POST
    @Path("/refresh")
    @PermitAll
    public Response refresh(RefreshRequest req) {
        return Response.ok(authService.refresh(req)).build();
    }

    @POST
    @Path("/forgot-password")
    @PermitAll
    public Response forgotPassword(com.web.model.ForgotPasswordRequest req) {
        authService.forgotPassword(req);
        return Response.ok().build();
    }

    @POST
    @Path("/reset-password")
    @PermitAll
    public Response resetPassword(com.web.model.ResetPasswordRequest req) {
        authService.resetPassword(req);
        return Response.ok().build();
    }
}
