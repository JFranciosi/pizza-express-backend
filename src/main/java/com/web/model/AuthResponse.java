package com.web.model;

public class AuthResponse {
    public String accessToken;
    public String refreshToken;
    public String userId;
    public String username;
    public String email;
    public Double balance;

    public AuthResponse(String accessToken, String refreshToken, String userId, String username, String email,
            Double balance) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.userId = userId;
        this.username = username;
        this.email = email;
        this.balance = balance;
    }
}
