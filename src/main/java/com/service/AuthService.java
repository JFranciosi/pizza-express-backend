package com.service;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.model.Player;
import com.repository.PlayerRepository;
import com.web.model.AuthResponse;
import com.web.model.LoginRequest;
import com.web.model.RegisterRequest;
import com.web.model.RefreshRequest;
import com.web.model.ForgotPasswordRequest;
import com.web.model.ResetPasswordRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotAuthorizedException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.web.error.AuthenticationFailedException;
import com.web.error.UserAlreadyExistsException;
import java.util.UUID;

@ApplicationScoped
public class AuthService {

    private final PlayerRepository playerRepository;
    private final TokenService tokenService;
    private final EmailService emailService;
    private final String frontendUrl;

    private static final String DUMMY_HASH = "$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquii.V37YoLW5I477x8p6";

    @Inject
    public AuthService(PlayerRepository playerRepository,
            TokenService tokenService,
            EmailService emailService,
            @ConfigProperty(name = "app.frontend.url", defaultValue = "http://localhost:4200") String frontendUrl) {
        this.playerRepository = playerRepository;
        this.tokenService = tokenService;
        this.emailService = emailService;
        this.frontendUrl = frontendUrl;
    }

    public AuthResponse register(RegisterRequest req) {
        if (playerRepository.existsByEmail(req.email())) {
            throw new UserAlreadyExistsException("Email already in use");
        }
        if (playerRepository.existsByUsername(req.username())) {
            throw new UserAlreadyExistsException("Username already in use");
        }

        validatePassword(req.password());

        String hashedPassword = BCrypt.withDefaults().hashToString(12, req.password().toCharArray());
        String id = UUID.randomUUID().toString();

        Player player = new Player(
                id,
                req.username(),
                req.email(),
                hashedPassword,
                500.0);

        playerRepository.save(player);
        String accessToken = tokenService.generateAccessToken(player.getEmail(), player.getUsername(), player.getId());
        String refreshToken = tokenService.generateRefreshToken();
        playerRepository.saveRefreshToken(refreshToken, player.getId());

        return new AuthResponse(accessToken, refreshToken, player.getId(), player.getUsername(), player.getEmail(),
                player.getBalance(), player.getAvatarUrl());
    }

    public AuthResponse login(LoginRequest req) {
        Player player = playerRepository.findByEmail(req.email());

        if (player == null) {
            BCrypt.verifyer().verify(req.password().toCharArray(), DUMMY_HASH);
            throw new AuthenticationFailedException("Invalid credentials");
        }

        BCrypt.Result result = BCrypt.verifyer().verify(req.password().toCharArray(), player.getPasswordHash());
        if (!result.verified) {
            throw new AuthenticationFailedException("Invalid credentials");
        }

        playerRepository.ensureIndices(player);

        String accessToken = tokenService.generateAccessToken(player.getEmail(), player.getUsername(), player.getId());
        String refreshToken = tokenService.generateRefreshToken();
        playerRepository.saveRefreshToken(refreshToken, player.getId());

        return new AuthResponse(accessToken, refreshToken, player.getId(), player.getUsername(), player.getEmail(),
                player.getBalance(), player.getAvatarUrl());
    }

    public AuthResponse refresh(RefreshRequest req) {
        String playerId = playerRepository.validateRefreshToken(req.refreshToken());
        if (playerId == null) {
            throw new NotAuthorizedException("Invalid refresh token");
        }

        Player player = playerRepository.findById(playerId);
        if (player == null) {
            throw new NotAuthorizedException("User not found");
        }

        playerRepository.deleteRefreshToken(req.refreshToken());

        String accessToken = tokenService.generateAccessToken(player.getEmail(), player.getUsername(), player.getId());
        String newRefreshToken = tokenService.generateRefreshToken();
        playerRepository.saveRefreshToken(newRefreshToken, player.getId());

        return new AuthResponse(accessToken, newRefreshToken, player.getId(), player.getUsername(), player.getEmail(),
                player.getBalance(), player.getAvatarUrl());
    }

    public void logout(String refreshToken) {
        if (refreshToken != null) {
            playerRepository.deleteRefreshToken(refreshToken);
        }
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

        validatePassword(newPass);

        String newHashed = BCrypt.withDefaults().hashToString(12, newPass.toCharArray());
        player.setPasswordHash(newHashed);
        playerRepository.save(player);

        // Invalidate all active sessions
        playerRepository.deleteAllTokensForUser(userId);
    }

    public void updateEmail(String userId, String newEmail, String password) {
        Player player = playerRepository.findById(userId);
        if (player == null) {
            throw new NotAuthorizedException("User not found");
        }

        if (password == null || password.isEmpty()) {
            throw new BadRequestException("Password required");
        }
        BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), player.getPasswordHash());
        if (!result.verified) {
            throw new BadRequestException("Invalid password");
        }

        if (player.getEmail().equals(newEmail)) {
            return;
        }

        if (playerRepository.existsByEmail(newEmail)) {
            throw new UserAlreadyExistsException("Email already in use");
        }

        playerRepository.removeLookups(player.getEmail(), null);

        player.setEmail(newEmail);
        playerRepository.save(player);
    }

    public void updateAvatar(String userId, String avatarUrl) {
        Player player = playerRepository.findById(userId);
        if (player == null) {
            throw new NotAuthorizedException("User not found");
        }
        player.setAvatarUrl(avatarUrl);
        playerRepository.save(player);
    }

    public void forgotPassword(ForgotPasswordRequest req) {
        Thread.ofVirtual().start(() -> {
            Player player = playerRepository.findByEmail(req.email());

            if (player != null) {
                if (playerRepository.isEmailRateLimited(req.email())) {
                    return;
                }

                String token = UUID.randomUUID().toString();
                playerRepository.saveResetToken(token, player.getId());
                String resetLink = frontendUrl + "/reset-password?token=" + token;
                emailService.sendPasswordResetEmail(player.getEmail(), resetLink);
            }
        });
    }

    public void resetPassword(ResetPasswordRequest req) {
        String playerId = playerRepository.validateResetToken(req.token());
        if (playerId == null) {
            throw new BadRequestException("Invalid or expired token");
        }

        Player player = playerRepository.findById(playerId);
        if (player == null) {
            throw new BadRequestException("User not found");
        }

        validatePassword(req.newPassword());

        String newHashed = BCrypt.withDefaults().hashToString(12, req.newPassword().toCharArray());
        player.setPasswordHash(newHashed);
        playerRepository.save(player);

        playerRepository.deleteResetToken(req.token());
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new BadRequestException("Password must be at least 8 characters long");
        }
        if (!password.matches(".*[A-Z].*")) {
            throw new BadRequestException("Password must contain at least one uppercase letter");
        }
        if (!password.matches(".*[a-z].*")) {
            throw new BadRequestException("Password must contain at least one lowercase letter");
        }
        if (!password.matches(".*\\d.*")) {
            throw new BadRequestException("Password must contain at least one number");
        }
        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*")) {
            throw new BadRequestException("Password must contain at least one special character");
        }
    }
}
