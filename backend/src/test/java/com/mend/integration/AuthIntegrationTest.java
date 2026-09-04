package com.mend.integration;

import com.mend.dto.BootstrapRequest;
import com.mend.dto.BootstrapResponse;
import com.mend.dto.LoginRequest;
import com.mend.dto.LoginResponse;
import com.mend.dto.UserDto;
import com.mend.exception.AuthenticationException;
import com.mend.exception.InvalidRequestException;
import com.mend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
public class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuthService authService;

    @Test
    public void testBootstrapAndLoginFlow() {
        BootstrapRequest bootstrapReq = new BootstrapRequest(
                "Acme Corp",
                "admin_test_flow@acme.com",
                "AdminPass123!",
                "Acme Admin"
        );

        BootstrapResponse bootstrapRes = authService.bootstrap(bootstrapReq);
        assertNotNull(bootstrapRes);
        assertNotNull(bootstrapRes.getMerchantId());
        assertNotNull(bootstrapRes.getUserId());
        assertEquals("Acme Corp", bootstrapRes.getMerchantName());
        assertEquals("admin_test_flow@acme.com", bootstrapRes.getUserEmail());
        assertEquals("MERCHANT_ADMIN", bootstrapRes.getRoleName());

        // Login with valid credentials
        LoginRequest loginReq = new LoginRequest("admin_test_flow@acme.com", "AdminPass123!");
        LoginResponse loginRes = authService.login(loginReq);

        assertNotNull(loginRes.getToken());
        assertTrue(loginRes.getExpiresIn() > 0);
        assertNotNull(loginRes.getUser());
        assertEquals("admin_test_flow@acme.com", loginRes.getUser().getEmail());
        assertFalse(loginRes.getUser().getMemberships().isEmpty());
        assertEquals("MERCHANT_ADMIN", loginRes.getUser().getMemberships().get(0).getRoleName());
    }

    @Test
    public void testLoginInvalidPassword() {
        BootstrapRequest bootstrapReq = new BootstrapRequest("Corp B", "user@corpb.com", "CorrectPassword123!", "User B");
        authService.bootstrap(bootstrapReq);

        LoginRequest invalidLogin = new LoginRequest("user@corpb.com", "WrongPassword123!");
        assertThrows(AuthenticationException.class, () -> authService.login(invalidLogin));
    }

    @Test
    public void testLoginUnknownUser() {
        LoginRequest unknownUserLogin = new LoginRequest("nonexistent@example.com", "Password123!");
        assertThrows(AuthenticationException.class, () -> authService.login(unknownUserLogin));
    }

    @Test
    public void testLoginMissingFields() {
        assertThrows(InvalidRequestException.class, () -> authService.login(new LoginRequest(null, "pass")));
        assertThrows(InvalidRequestException.class, () -> authService.login(new LoginRequest("email@test.com", null)));
    }
}
