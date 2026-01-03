package com.web;

import com.model.Player;
import com.repository.PlayerRepository;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import java.util.Base64;

@Path("/users")
@RunOnVirtualThread
public class UserResource {

    private final PlayerRepository playerRepository;
    private final com.service.FileStorageService fileStorageService;

    @Inject
    public UserResource(PlayerRepository playerRepository, com.service.FileStorageService fileStorageService) {
        this.playerRepository = playerRepository;
        this.fileStorageService = fileStorageService;
    }

    @GET
    @Path("/{userId}/avatar")
    public Response getAvatar(@PathParam("userId") String userId) {
        Player player = playerRepository.findById(userId);
        if (player == null || player.getAvatarUrl() == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String avatarData = player.getAvatarUrl();

        if (avatarData.startsWith("data:")) {
            try {
                String[] parts = avatarData.split(",");
                if (parts.length < 2) {
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
                }

                String header = parts[0];
                String base64Content = parts[1];

                String mimeType = "image/jpeg";
                if (header.contains(";") && header.startsWith("data:")) {
                    mimeType = header.substring(5, header.indexOf(";"));
                }

                byte[] imageBytes = Base64.getDecoder().decode(base64Content);
                return Response.ok(imageBytes)
                        .type(mimeType)
                        .header("X-Content-Type-Options", "nosniff")
                        .build();
            } catch (Exception e) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        }

        try {
            byte[] imageBytes = fileStorageService.loadAvatar(avatarData);
            if (imageBytes == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            String mimeType = "image/jpeg";
            if (avatarData.endsWith(".png"))
                mimeType = "image/png";
            else if (avatarData.endsWith(".webp"))
                mimeType = "image/webp";
            else if (avatarData.endsWith(".ico"))
                mimeType = "image/x-icon";
            else if (avatarData.endsWith(".gif"))
                mimeType = "image/gif";

            return Response.ok(imageBytes)
                    .type(mimeType)
                    .header("X-Content-Type-Options", "nosniff")
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

}
