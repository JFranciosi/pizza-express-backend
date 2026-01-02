package com.web;

import com.service.AuthService;
import com.web.model.*;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.validation.Valid;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import static javax.imageio.ImageIO.read;
import static javax.imageio.ImageIO.write;

@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class AuthResource {

    public record ChangePasswordRequest(String userId, String oldPass, String newPass) {
    }

    public record UpdateProfileRequest(String email, String password) {
    }

    public static class AvatarUploadRequest {
        @RestForm("file")
        public FileUpload file;
    }

    private final AuthService authService;
    private final String frontendUrl;
    private final org.eclipse.microprofile.jwt.JsonWebToken jwt;

    @Inject
    public AuthResource(AuthService authService,
            @ConfigProperty(name = "app.frontend.url") String frontendUrl,
            org.eclipse.microprofile.jwt.JsonWebToken jwt) {
        this.authService = authService;
        this.frontendUrl = frontendUrl;
        this.jwt = jwt;
    }

    @POST
    @Path("/change-password")
    @Authenticated
    public Response changePassword(ChangePasswordRequest req) {
        String userId = jwt.getClaim("userId");
        authService.changePassword(userId, req.oldPass(), req.newPass());
        return Response.ok().build();
    }

    @POST
    @Path("/update-profile")
    @Authenticated
    public Response updateProfile(UpdateProfileRequest req) {
        String userId = jwt.getClaim("userId");
        authService.updateEmail(userId, req.email(), req.password());
        return Response.ok().build();
    }

    @POST
    @Path("/upload-avatar")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @io.quarkus.security.Authenticated
    public Response uploadAvatar(AvatarUploadRequest req) throws IOException {
        String userId = jwt.getClaim("userId");

        if (req.file == null) {
            throw new BadRequestException("File is required");
        }

        if (req.file.size() > 2 * 1024 * 1024) {
            throw new BadRequestException("File too large (max 2MB)");
        }

        java.awt.image.BufferedImage image = read(req.file.filePath().toFile());
        if (image == null) {
            throw new BadRequestException("Invalid image file or format");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        write(image, "jpg", baos);
        byte[] sanitizedBytes = baos.toByteArray();

        String base64Img = java.util.Base64.getEncoder().encodeToString(sanitizedBytes);
        String avatarUrl = "data:image/jpeg;base64," + base64Img;

        authService.updateAvatar(userId, avatarUrl);
        return Response.ok(new java.util.HashMap<String, String>() {
            {
                put("avatarUrl", avatarUrl);
            }
        }).build();
    }

    @POST
    @Path("/register")
    @PermitAll
    public Response register(@Valid RegisterRequest req) {
        var authResponse = authService.register(req);
        return createTokenResponse(authResponse);
    }

    @POST
    @Path("/login")
    @PermitAll
    public Response login(@Valid LoginRequest req) {
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
                    .entity(new ErrorResponse(e.getMessage())).build();
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
                    .entity(new ErrorResponse(e.getMessage())).build();
        }

    }

    @POST
    @Path("/logout")
    @PermitAll
    public Response logout() {
        boolean isSecure = frontendUrl != null && frontendUrl.startsWith("https");

        NewCookie refreshCookie = new NewCookie.Builder("refresh_token")
                .value("")
                .path("/auth/refresh")
                .httpOnly(true)
                .secure(isSecure)
                .maxAge(0)
                .build();

        NewCookie accessCookie = new NewCookie.Builder("access_token")
                .value("")
                .path("/")
                .httpOnly(true)
                .secure(isSecure)
                .maxAge(0)
                .build();

        return Response.ok().cookie(refreshCookie, accessCookie).build();
    }

    private Response createTokenResponse(AuthResponse authResponse) {
        boolean isSecure = frontendUrl != null && frontendUrl.startsWith("https");

        NewCookie refreshCookie = new NewCookie.Builder("refresh_token")
                .value(authResponse.refreshToken())
                .path("/auth/refresh")
                .httpOnly(true)
                .secure(isSecure)
                .maxAge(7 * 24 * 60 * 60)
                .build();

        NewCookie accessCookie = new NewCookie.Builder("access_token")
                .value(authResponse.accessToken())
                .path("/")
                .httpOnly(true)
                .secure(isSecure)
                .maxAge(15 * 60)
                .build();

        var scrubbedResponse = new AuthResponse(
                null,
                null,
                authResponse.userId(),
                authResponse.username(),
                authResponse.email(),
                authResponse.balance(),
                authResponse.avatarUrl());

        return Response.ok(scrubbedResponse)
                .cookie(refreshCookie, accessCookie)
                .build();
    }
}
