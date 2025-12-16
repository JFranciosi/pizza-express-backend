package com.service;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

@ApplicationScoped
public class TokenService {

    public String generateAccessToken(String email, String username, String userId) {
        return Jwt.issuer("https://pizza-express.com/issuer")
                .upn(email)
                .claim("username", username)
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

    @jakarta.inject.Inject
    io.smallrye.jwt.auth.principal.JWTParser parser;

    public String getUserIdFromToken(String token) throws io.smallrye.jwt.auth.principal.ParseException {
        // Remove "Bearer " prefix if present
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        return parser.parse(token).getClaim("userId");
    }

    public String getUsernameFromToken(String token) throws io.smallrye.jwt.auth.principal.ParseException {
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        return parser.parse(token).getClaim("username");
    }
}
