package com.mend.controller;

import com.mend.dto.*;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> getCurrentUser(@CurrentUser AuthenticatedUser currentUser) {
        UserDto response = authService.getCurrentUser(currentUser);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/bootstrap")
    public ResponseEntity<BootstrapResponse> bootstrap(@RequestBody BootstrapRequest request) {
        BootstrapResponse response = authService.bootstrap(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
