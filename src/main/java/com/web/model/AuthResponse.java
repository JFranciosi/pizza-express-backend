package com.web.model;

public record AuthResponse(String accessToken, String refreshToken, String userId,
        String username, String email, Double balance, String avatarUrl) {
}
