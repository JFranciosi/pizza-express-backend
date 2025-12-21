package com.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@ApplicationScoped
public class CryptoService {

    @ConfigProperty(name = "JWT_PRIVATE_KEY")
    String rawPrivateKey;

    @ConfigProperty(name = "JWT_PUBLIC_KEY")
    String rawPublicKey;

    public PrivateKey getPrivateKey() throws Exception {
        // CORREZIONE FONDAMENTALE PER AZURE
        // Sostituisce i caratteri letterali "\n" con dei veri "a capo" se presenti
        if (rawPrivateKey == null || rawPrivateKey.isEmpty()) {
            throw new RuntimeException("JWT_PRIVATE_KEY is missing via CryptoService!");
        }

        String realPem = rawPrivateKey.contains("\\n") ? rawPrivateKey.replace("\\n", "\n") : rawPrivateKey;

        // Pulizia per Java (Java vuole solo il Base64 puro, senza
        // header/footer/newlines)
        String privateKeyPEM = realPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", ""); // Rimuove spazi e newlines rimasti

        byte[] encoded = Base64.getDecoder().decode(privateKeyPEM);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
        return keyFactory.generatePrivate(keySpec);
    }

    public PublicKey getPublicKey() throws Exception {
        if (rawPublicKey == null || rawPublicKey.isEmpty()) {
            throw new RuntimeException("JWT_PUBLIC_KEY is missing via CryptoService!");
        }

        String realPem = rawPublicKey.contains("\\n") ? rawPublicKey.replace("\\n", "\n") : rawPublicKey;

        String publicKeyPEM = realPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        byte[] encoded = Base64.getDecoder().decode(publicKeyPEM);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encoded);
        return keyFactory.generatePublic(keySpec);
    }
}
