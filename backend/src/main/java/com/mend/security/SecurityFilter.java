package com.mend.security;

import tools.jackson.databind.ObjectMapper;
import com.mend.dto.ErrorResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class SecurityFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserPrincipalResolver userPrincipalResolver;
    private final ObjectMapper objectMapper;

    public SecurityFilter(JwtService jwtService, UserPrincipalResolver userPrincipalResolver, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.userPrincipalResolver = userPrincipalResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Allow public endpoints
        if (isPublicPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
            return;
        }

        String token = authHeader.substring(7).trim();
        if (!jwtService.validateToken(token)) {
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
            return;
        }

        try {
            UUID userId = jwtService.getUserIdFromToken(token);
            AuthenticatedUser authenticatedUser = userPrincipalResolver.resolveUser(userId);

            if (authenticatedUser == null || !"ACTIVE".equalsIgnoreCase(authenticatedUser.getStatus())) {
                writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "User account is inactive or not found");
                return;
            }

            UUID merchantId = null;
            String merchantHeader = request.getHeader("X-Merchant-Id");
            if (merchantHeader != null && !merchantHeader.trim().isEmpty()) {
                try {
                    merchantId = UUID.fromString(merchantHeader.trim());
                } catch (IllegalArgumentException e) {
                    writeErrorResponse(response, HttpStatus.BAD_REQUEST, "Invalid X-Merchant-Id header format");
                    return;
                }
            }

            TenantContext.setTenant(authenticatedUser, merchantId);

            filterChain.doFilter(request, response);

        } catch (Exception e) {
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "Authentication failed: " + e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private boolean isPublicPath(String path) {
        return path.startsWith("/api/v1/auth/login") ||
               path.startsWith("/api/v1/auth/bootstrap") ||
               path.startsWith("/api/v1/webhooks/razorpay") ||
               path.startsWith("/api/v1/health") ||
               path.startsWith("/api/health") ||
               path.startsWith("/error");
    }

    private void writeErrorResponse(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse errorResponse = new ErrorResponse(status.value(), status.getReasonPhrase(), message);
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
