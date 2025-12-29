package com.web;

import com.model.Player;
import com.repository.PlayerRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import java.util.Base64;

@Path("/users")
@io.smallrye.common.annotation.RunOnVirtualThread
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
            if (header.contains(";")) {
                mimeType = header.split(";")[0].replace("data:", "");
            }

            byte[] imageBytes = Base64.getDecoder().decode(base64Content);

            return Response.ok(imageBytes).type(mimeType).build();

        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }
}
