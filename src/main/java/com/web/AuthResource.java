package com.web;

import com.service.AuthService;
import com.web.model.*;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@io.smallrye.common.annotation.RunOnVirtualThread
public class AuthResource {

    public record ChangePasswordRequest(String userId, String oldPass, String newPass) {
    }

    public record UpdateProfileRequest(String email, String password) {
    }

    public static class AvatarUploadRequest {
        @org.jboss.resteasy.reactive.RestForm("file")
        public org.jboss.resteasy.reactive.multipart.FileUpload file;
    }

    private final AuthService authService;
    private final com.service.TokenService tokenService;
    private final String frontendUrl;

    @Inject
    public AuthResource(AuthService authService,
            com.service.TokenService tokenService,
            @ConfigProperty(name = "app.frontend.url") String frontendUrl) {
        this.authService = authService;
        this.tokenService = tokenService;
        this.frontendUrl = frontendUrl;
    }

    @POST
    @Path("/change-password")
    public Response changePassword(@jakarta.ws.rs.HeaderParam("Authorization") String token,
            ChangePasswordRequest req) {
        try {
            String userId = tokenService.getUserIdFromToken(token);
            authService.changePassword(userId, req.oldPass(), req.newPass());
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
            authService.updateEmail(userId, req.email(), req.password());
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new com.web.BettingResource.ErrorResponse(e.getMessage())).build();
        }
    }

    @POST
    @Path("/upload-avatar")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadAvatar(@jakarta.ws.rs.HeaderParam("Authorization") String token,
            AvatarUploadRequest req) {
        try {
            String userId = tokenService.getUserIdFromToken(token);

            if (req.file == null) {
                return Response.status(Response.Status.BAD_REQUEST).entity("File is required").build();
            }

            if (req.file.size() > 2 * 1024 * 1024) {
                return Response.status(Response.Status.BAD_REQUEST).entity("File too large (max 2MB)").build();
            }

            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(req.file.filePath().toFile());
            if (image == null) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Invalid image file or format").build();
            }

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "jpg", baos);
            byte[] sanitizedBytes = baos.toByteArray();

            String base64Img = java.util.Base64.getEncoder().encodeToString(sanitizedBytes);
            String avatarUrl = "data:image/jpeg;base64," + base64Img;

            authService.updateAvatar(userId, avatarUrl);
            return Response.ok(new java.util.HashMap<String, String>() {
                {
                    put("avatarUrl", avatarUrl);
                }
            }).build();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new com.web.BettingResource.ErrorResponse(e.getMessage())).build();
        }
    }

    @POST
    @Path("/register")
    @PermitAll
    public Response register(RegisterRequest req) {
        var authResponse = authService.register(req);
        return createTokenResponse(authResponse);
    }

    @POST
    @Path("/login")
    @PermitAll
    public Response login(LoginRequest req) {
        var authResponse = authService.login(req);
        return createTokenResponse(authResponse);
    }

    @POST
    @Path("/refresh")
    @PermitAll
    public Response refresh(@CookieParam("refresh_token") String cookieRefreshToken, RefreshRequest req) {
        String tokenToUse = cookieRefreshToken;
        if (tokenToUse == null && req != null) {
            tokenToUse = req.refreshToken();
        }

        if (tokenToUse == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("No refresh token provided").build();
        }

        var authResponse = authService.refresh(new RefreshRequest(tokenToUse));
        return createTokenResponse(authResponse);
    }

    @POST
    @Path("/forgot-password")
    @PermitAll
    public Response forgotPassword(com.web.model.ForgotPasswordRequest req) {
        try {
            authService.forgotPassword(req);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new com.web.BettingResource.ErrorResponse(e.getMessage())).build();
        }
    }

    @POST
    @Path("/reset-password")
    @PermitAll
    public Response resetPassword(ResetPasswordRequest req) {
        try {
            authService.resetPassword(req);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new BettingResource.ErrorResponse(e.getMessage())).build();
        }
    }

    private Response createTokenResponse(AuthResponse authResponse) {
        boolean isSecure = frontendUrl != null && frontendUrl.startsWith("https");
        jakarta.ws.rs.core.NewCookie refreshCookie = new jakarta.ws.rs.core.NewCookie.Builder("refresh_token")
                .value(authResponse.refreshToken())
                .path("/auth/refresh")
                .httpOnly(true)
                .secure(isSecure)
                .maxAge(7 * 24 * 60 * 60)
                .build();

        var scrubbedResponse = new AuthResponse(
                authResponse.accessToken(),
                null,
                authResponse.userId(),
                authResponse.username(),
                authResponse.email(),
                authResponse.balance(),
                authResponse.avatarUrl());

        return Response.ok(scrubbedResponse).cookie(refreshCookie).build();
    }
}
