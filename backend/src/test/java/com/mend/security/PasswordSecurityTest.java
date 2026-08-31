package com.mend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PasswordSecurityTest {

    private PasswordHasher passwordHasher;

    @BeforeEach
    public void setUp() {
        passwordHasher = new PasswordHasher();
    }

    @Test
    public void testPasswordHashingAndVerification() {
        String rawPassword = "SecurePassword123!";
        String hash = passwordHasher.hashPassword(rawPassword);

        assertNotNull(hash);
        assertTrue(hash.startsWith("pbkdf2:"));
        assertNotEquals(rawPassword, hash);

        assertTrue(passwordHasher.verifyPassword(rawPassword, hash));
        assertFalse(passwordHasher.verifyPassword("WrongPassword", hash));
    }

    @Test
    public void testUniqueSaltsForSamePassword() {
        String rawPassword = "SamePassword123!";
        String hash1 = passwordHasher.hashPassword(rawPassword);
        String hash2 = passwordHasher.hashPassword(rawPassword);

        assertNotEquals(hash1, hash2);
        assertTrue(passwordHasher.verifyPassword(rawPassword, hash1));
        assertTrue(passwordHasher.verifyPassword(rawPassword, hash2));
    }

    @Test
    public void testInvalidPasswordInputs() {
        assertThrows(IllegalArgumentException.class, () -> passwordHasher.hashPassword(null));
        assertThrows(IllegalArgumentException.class, () -> passwordHasher.hashPassword(""));

        assertFalse(passwordHasher.verifyPassword(null, "pbkdf2:65536:salt:hash"));
        assertFalse(passwordHasher.verifyPassword("password", null));
        assertFalse(passwordHasher.verifyPassword("password", "invalid_hash_format"));
    }
}
