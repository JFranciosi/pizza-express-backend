package com.service;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

@ApplicationScoped
public class TokenService {

    public String generateAccessToken(String email, String userId) {
        return Jwt.issuer("https://pizza-express.com/issuer")
                .upn(email)
                .groups(Set.of("User"))
                .claim("userId", userId)
                .expiresIn(3600) // 1 hour
                .sign();
    }

    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
