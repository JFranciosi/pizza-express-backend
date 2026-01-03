package com.web;

import com.service.AuthService;
import com.service.FileStorageService;
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
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;

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
    private final FileStorageService fileStorageService;

    @Inject
    public AuthResource(AuthService authService,
            @ConfigProperty(name = "app.frontend.url") String frontendUrl,
            org.eclipse.microprofile.jwt.JsonWebToken jwt,
            FileStorageService fileStorageService) {
        this.authService = authService;
        this.frontendUrl = frontendUrl;
        this.jwt = jwt;
        this.fileStorageService = fileStorageService;
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
    @Authenticated
    public Response uploadAvatar(AvatarUploadRequest req) throws IOException {
        String userId = jwt.getClaim("userId");

        if (req.file == null) {
            throw new BadRequestException("File is required");
        }

        if (req.file.size() > 2 * 1024 * 1024) {
            throw new BadRequestException("File too large (max 2MB)");
        }

        byte[] fileBytes = Files.readAllBytes(req.file.filePath());
        String mimeType = detectMimeType(fileBytes);

        if (mimeType == null) {
            throw new BadRequestException("Unsupported file format. Allowed: JPEG, PNG, WEBP, ICO");
        }

        String avatarPath = fileStorageService.saveAvatar(userId, fileBytes, mimeType);

        authService.updateAvatar(userId, avatarPath);

        return Response.ok(new HashMap<String, String>() {
            {
                put("avatarUrl", avatarPath);
            }
        }).build();
    }

    private String detectMimeType(byte[] data) {
        if (data.length < 12)
            return null;

        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }

        if ((data[0] & 0xFF) == 0x89 && (data[1] & 0xFF) == 0x50 && (data[2] & 0xFF) == 0x4E &&
                (data[3] & 0xFF) == 0x47 && (data[4] & 0xFF) == 0x0D && (data[5] & 0xFF) == 0x0A &&
                (data[6] & 0xFF) == 0x1A && (data[7] & 0xFF) == 0x0A) {
            return "image/png";
        }

        if ((data[0] & 0xFF) == 0x52 && (data[1] & 0xFF) == 0x49 && (data[2] & 0xFF) == 0x46 && (data[3] & 0xFF) == 0x46
                &&
                (data[8] & 0xFF) == 0x57 && (data[9] & 0xFF) == 0x45 && (data[10] & 0xFF) == 0x42
                && (data[11] & 0xFF) == 0x50) {
            return "image/webp";
        }

        if ((data[0] & 0xFF) == 0x00 && (data[1] & 0xFF) == 0x00 && (data[2] & 0xFF) == 0x01
                && (data[3] & 0xFF) == 0x00) {
            return "image/x-icon";
        }

        return null;
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
    public Response logout(@CookieParam("refresh_token") String refreshToken) {
        authService.logout(refreshToken);

        boolean isSecure = frontendUrl != null && frontendUrl.startsWith("https");
        NewCookie.SameSite sameSite = isSecure ? NewCookie.SameSite.NONE : NewCookie.SameSite.LAX;

        NewCookie refreshCookie = new NewCookie.Builder("refresh_token")
                .value("")
                .path("/")
                .httpOnly(true)
                .secure(isSecure)
                .sameSite(sameSite)
                .maxAge(0)
                .build();

        NewCookie accessCookie = new NewCookie.Builder("access_token")
                .value("")
                .path("/")
                .httpOnly(true)
                .secure(isSecure)
                .sameSite(sameSite)
                .maxAge(0)
                .build();

        return Response.ok().cookie(refreshCookie, accessCookie).build();
    }

    private Response createTokenResponse(AuthResponse authResponse) {
        boolean isSecure = frontendUrl != null && frontendUrl.startsWith("https");
        NewCookie.SameSite sameSite = isSecure ? NewCookie.SameSite.NONE : NewCookie.SameSite.LAX;

        NewCookie refreshCookie = new NewCookie.Builder("refresh_token")
                .value(authResponse.refreshToken())
                .path("/")
                .httpOnly(true)
                .secure(isSecure)
                .maxAge(7 * 24 * 60 * 60)
                .sameSite(sameSite)
                .build();

        NewCookie accessCookie = new NewCookie.Builder("access_token")
                .value(authResponse.accessToken())
                .path("/")
                .httpOnly(true)
                .secure(isSecure)
                .maxAge(15 * 60)
                .sameSite(sameSite)
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
