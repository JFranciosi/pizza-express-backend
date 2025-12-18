package com.service;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.model.Player;
import com.repository.PlayerRepository;
import com.web.model.AuthResponse;
import com.web.model.LoginRequest;
import com.web.model.RegisterRequest;
import com.web.model.RefreshRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotAuthorizedException;

import java.util.UUID;

@ApplicationScoped
public class AuthService {

    @Inject
    PlayerRepository playerRepository;

    @Inject
    TokenService tokenService;

    public AuthResponse register(RegisterRequest req) {
        if (playerRepository.existsByEmail(req.email)) {
            throw new BadRequestException("Email already in use");
        }

        String hashedPassword = BCrypt.withDefaults().hashToString(12, req.password.toCharArray());
        String id = UUID.randomUUID().toString();

        Player player = new Player(
                id,
                req.username,
                req.email,
                hashedPassword,
                500.0);

        playerRepository.save(player);
        String accessToken = tokenService.generateAccessToken(player.getEmail(), player.getUsername(), player.getId());
        String refreshToken = tokenService.generateRefreshToken();
        playerRepository.saveRefreshToken(refreshToken, player.getId());

        return new AuthResponse(accessToken, refreshToken, player.getId(), player.getUsername(), player.getEmail(),
                player.getBalance());
    }

    public AuthResponse login(LoginRequest req) {
        Player player = playerRepository.findByEmail(req.email);
        if (player == null) {
            throw new NotAuthorizedException("Invalid credentials");
        }

        BCrypt.Result result = BCrypt.verifyer().verify(req.password.toCharArray(), player.getPasswordHash());
        if (!result.verified) {
            throw new NotAuthorizedException("Invalid credentials");
        }

        String accessToken = tokenService.generateAccessToken(player.getEmail(), player.getUsername(), player.getId());
        String refreshToken = tokenService.generateRefreshToken();
        playerRepository.saveRefreshToken(refreshToken, player.getId());

        return new AuthResponse(accessToken, refreshToken, player.getId(), player.getUsername(), player.getEmail(),
                player.getBalance());
    }

    public AuthResponse refresh(RefreshRequest req) {
        String playerId = playerRepository.validateRefreshToken(req.refreshToken);
        if (playerId == null) {
            throw new NotAuthorizedException("Invalid refresh token");
        }

        Player player = playerRepository.findById(playerId);
        if (player == null) {
            throw new NotAuthorizedException("User not found");
        }

        // Revoke old refresh token (optional, but good practice for rotation)
        playerRepository.deleteRefreshToken(req.refreshToken);

        String accessToken = tokenService.generateAccessToken(player.getEmail(), player.getUsername(), player.getId());
        String newRefreshToken = tokenService.generateRefreshToken();
        playerRepository.saveRefreshToken(newRefreshToken, player.getId());

        return new AuthResponse(accessToken, newRefreshToken, player.getId(), player.getUsername(), player.getEmail(),
                player.getBalance());
    }

    public void changePassword(String userId, String oldPass, String newPass) {
        Player player = playerRepository.findById(userId);
        if (player == null) {
            throw new NotAuthorizedException("User not found");
        }

        BCrypt.Result result = BCrypt.verifyer().verify(oldPass.toCharArray(), player.getPasswordHash());
        if (!result.verified) {
            throw new BadRequestException("Incorrect old password");
        }

        String newHashed = BCrypt.withDefaults().hashToString(12, newPass.toCharArray());
        player.setPasswordHash(newHashed);
        playerRepository.save(player);
    }
}
