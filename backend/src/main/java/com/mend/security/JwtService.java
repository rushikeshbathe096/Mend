package com.mend.security;

import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class JwtService {

    private final String secret;
    private final long expirationMs;
    private final ObjectMapper objectMapper;

    public JwtService(
            @Value("${jwt.secret:mend_default_secure_jwt_secret_key_for_payment_recovery_2026_production}") String secret,
            @Value("${jwt.expiration:86400000}") long expirationMs,
            ObjectMapper objectMapper) {
        this.secret = secret;
        this.expirationMs = expirationMs;
        this.objectMapper = objectMapper;
    }

    public String generateToken(UUID userId, String email, List<String> roles) {
        try {
            long now = System.currentTimeMillis();
            long exp = now + expirationMs;

            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", email);
            payload.put("userId", userId.toString());
            payload.put("email", email);
            payload.put("roles", roles != null ? roles : Collections.emptyList());
            payload.put("iat", now / 1000);
            payload.put("exp", exp / 1000);

            String encodedHeader = base64UrlEncode(objectMapper.writeValueAsBytes(header));
            String encodedPayload = base64UrlEncode(objectMapper.writeValueAsBytes(payload));

            String dataToSign = encodedHeader + "." + encodedPayload;
            String signature = hmacSha256(dataToSign, secret);

            return dataToSign + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JWT token", e);
        }
    }

    public boolean validateToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }

        try {
            String dataToSign = parts[0] + "." + parts[1];
            String expectedSignature = hmacSha256(dataToSign, secret);
            if (!constantTimeEquals(parts[2], expectedSignature)) {
                return false;
            }

            byte[] payloadBytes = base64UrlDecode(parts[1]);
            Map<?, ?> payload = objectMapper.readValue(payloadBytes, Map.class);
            Number expNumber = (Number) payload.get("exp");
            if (expNumber != null) {
                long expSec = expNumber.longValue();
                long nowSec = System.currentTimeMillis() / 1000;
                if (nowSec >= expSec) {
                    return false; // Expired
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public UUID getUserIdFromToken(String token) {
        Map<?, ?> payload = parsePayload(token);
        String userIdStr = (String) payload.get("userId");
        return UUID.fromString(userIdStr);
    }

    public String getEmailFromToken(String token) {
        Map<?, ?> payload = parsePayload(token);
        return (String) payload.get("email");
    }

    private Map<?, ?> parsePayload(String token) {
        try {
            String[] parts = token.split("\\.");
            byte[] payloadBytes = base64UrlDecode(parts[1]);
            return objectMapper.readValue(payloadBytes, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JWT token format", e);
        }
    }

    private String hmacSha256(String data, String secretKey) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(rawHmac);
    }

    private String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] base64UrlDecode(String str) {
        return Base64.getUrlDecoder().decode(str);
    }

    private boolean constantTimeEquals(String a, String b) {
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        if (aBytes.length != bBytes.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        return result == 0;
    }
}
