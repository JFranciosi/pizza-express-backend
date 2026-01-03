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

    @Inject
    public UserResource(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    @GET
    @Path("/{userId}/avatar")
    public Response getAvatar(@PathParam("userId") String userId) {
        Player player = playerRepository.findById(userId);
        if (player == null || player.getAvatarUrl() == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String avatarData = player.getAvatarUrl();

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

            if (!mimeType.equals("image/jpeg") && !mimeType.equals("image/png") &&
                    !mimeType.equals("image/webp") && !mimeType.equals("image/x-icon")) {
                mimeType = "application/octet-stream";
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

}
