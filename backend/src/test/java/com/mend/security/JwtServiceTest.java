package com.mend.security;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    public void setUp() {
        jwtService = new JwtService("test_secret_key_123456789012345678901234567890", 3600000, new ObjectMapper());
    }

    @Test
    public void testGenerateAndValidateToken() {
        UUID userId = UUID.randomUUID();
        String email = "test@example.com";
        List<String> roles = List.of("MERCHANT_ADMIN");

        String token = jwtService.generateToken(userId, email, roles);
        assertNotNull(token);

        assertTrue(jwtService.validateToken(token));
        assertEquals(userId, jwtService.getUserIdFromToken(token));
        assertEquals(email, jwtService.getEmailFromToken(token));
    }

    @Test
    public void testInvalidTokenTampering() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, "test@example.com", List.of("REVIEWER"));

        String tamperedToken = token + "tampered";
        assertFalse(jwtService.validateToken(tamperedToken));
    }

    @Test
    public void testExpiredToken() {
        JwtService shortLivedJwtService = new JwtService("test_secret_key_123456789012345678901234567890", -1000, new ObjectMapper());
        String token = shortLivedJwtService.generateToken(UUID.randomUUID(), "expired@example.com", List.of("REVIEWER"));

        assertFalse(shortLivedJwtService.validateToken(token));
    }
}
