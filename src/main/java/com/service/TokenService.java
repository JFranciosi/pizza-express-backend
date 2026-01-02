package com.service;

import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

@ApplicationScoped
public class TokenService {

    private final CryptoService cryptoService;
    private final JWTParser parser;

    @Inject
    public TokenService(CryptoService cryptoService, JWTParser parser) {
        this.cryptoService = cryptoService;
        this.parser = parser;
    }

    public String generateAccessToken(String email, String username, String userId) {
        try {
            return Jwt.issuer("https://pizza-express.com/issuer")
                    .upn(email)
                    .claim("username", username)
                    .groups(Set.of("User"))
                    .claim("userId", userId)
                    .expiresIn(3600)
                    .sign(cryptoService.getPrivateKey());
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign JWT token", e);
        }
    }

    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String getUserIdFromToken(String token) throws ParseException {
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        return parser.parse(token).getClaim("userId");
    }

    public String getUsernameFromToken(String token) throws ParseException {
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        return parser.parse(token).getClaim("username");
    }
}
