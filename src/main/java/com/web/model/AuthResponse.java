package com.web.model;

public class AuthResponse {
    public String accessToken;
    public String refreshToken;
    public String userId;
    public String username;

    public AuthResponse(String accessToken, String refreshToken, String userId, String username) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.userId = userId;
        this.username = username;
    }
}
