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
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

@ApplicationScoped
public class AuthService {

    @Inject
    PlayerRepository playerRepository;

    @Inject
    TokenService tokenService;

    @Inject
    EmailService emailService;

    @ConfigProperty(name = "app.frontend.url", defaultValue = "http://localhost:4200")
    String frontendUrl;

    public AuthResponse register(RegisterRequest req) {
        if (playerRepository.existsByEmail(req.email)) {
            throw new BadRequestException("Email already in use");
        }
        if (playerRepository.existsByUsername(req.username)) {
            throw new BadRequestException("Username already in use");
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

        // Self-heal: ensure lookup keys exist for legacy users
        playerRepository.ensureIndices(player);

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

    public void updateEmail(String userId, String newEmail, String password) {
        Player player = playerRepository.findById(userId);
        if (player == null) {
            throw new NotAuthorizedException("User not found");
        }

        // Verify password
        if (password == null || password.isEmpty()) {
            throw new BadRequestException("Password required");
        }
        BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), player.getPasswordHash());
        if (!result.verified) {
            throw new BadRequestException("Invalid password");
        }

        if (player.getEmail().equals(newEmail)) {
            return; // No changes
        }

        if (playerRepository.existsByEmail(newEmail)) {
            throw new BadRequestException("Email already in use");
        }

        // Clean up old email lookup
        playerRepository.removeLookups(player.getEmail(), null);

        player.setEmail(newEmail);
        playerRepository.save(player);
    }

    public void forgotPassword(com.web.model.ForgotPasswordRequest req) {
        Player player = playerRepository.findByEmail(req.email);
        if (player == null) {
            // Silently return to prevent enumeration, or throw based on policy.
            // For UX we might want to just say "If email exists..."
            return;
        }

        String token = UUID.randomUUID().toString();
        playerRepository.saveResetToken(token, player.getId());

        String resetLink = frontendUrl + "/reset-password?token=" + token;
        emailService.sendPasswordResetEmail(player.getEmail(), resetLink);
    }

    public void resetPassword(com.web.model.ResetPasswordRequest req) {
        String playerId = playerRepository.validateResetToken(req.token);
        if (playerId == null) {
            throw new BadRequestException("Invalid or expired token");
        }

        Player player = playerRepository.findById(playerId);
        if (player == null) {
            throw new BadRequestException("User not found");
        }

        String newHashed = BCrypt.withDefaults().hashToString(12, req.newPassword.toCharArray());
        player.setPasswordHash(newHashed);
        playerRepository.save(player);

        playerRepository.deleteResetToken(req.token);
    }
}
