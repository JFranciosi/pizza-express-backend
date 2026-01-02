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

            String base64Content = parts[1];
            byte[] imageBytes = Base64.getDecoder().decode(base64Content);

            String mimeType;
            if (isJpeg(imageBytes)) {
                mimeType = "image/jpeg";
            } else if (isPng(imageBytes)) {
                mimeType = "image/png";
            } else {
                return Response.status(Response.Status.UNSUPPORTED_MEDIA_TYPE).build();
            }

            return Response.ok(imageBytes).type(mimeType).build();

        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    private boolean isJpeg(byte[] data) {
        if (data.length < 2)
            return false;
        return (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
    }

    private boolean isPng(byte[] data) {
        if (data.length < 8)
            return false;
        return (data[0] & 0xFF) == 0x89 && (data[1] & 0xFF) == 0x50 &&
                (data[2] & 0xFF) == 0x4E && (data[3] & 0xFF) == 0x47 &&
                (data[4] & 0xFF) == 0x0D && (data[5] & 0xFF) == 0x0A &&
                (data[6] & 0xFF) == 0x1A && (data[7] & 0xFF) == 0x0A;
    }
}
