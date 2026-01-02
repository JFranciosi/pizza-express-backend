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

    @Inject
    public AuthResource(AuthService authService,
            com.service.TokenService tokenService) {
        this.authService = authService;
        this.tokenService = tokenService;
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
    public Response resetPassword(com.web.model.ResetPasswordRequest req) {
        try {
            authService.resetPassword(req);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new com.web.BettingResource.ErrorResponse(e.getMessage())).build();
        }
    }
}
