package com.mend.controller;

import com.mend.dto.OperationalHealthDto;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system/health")
public class InternalSystemHealthController {

    private static final Logger log = LoggerFactory.getLogger(InternalSystemHealthController.class);

    private final StringRedisTemplate redisTemplate;

    @Autowired
    public InternalSystemHealthController(
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/operational")
    public ResponseEntity<OperationalHealthDto> getOperationalHealth(@CurrentUser AuthenticatedUser currentUser) {
        if (currentUser == null || (!currentUser.isSystemAdmin() && !isMerchantAdmin(currentUser))) {
            log.warn("Unauthorized operational health access attempt by user: {}", currentUser != null ? currentUser.getEmail() : "anonymous");
            throw new TenantAccessDeniedException("Access denied: System Administrator privileges required for operational health metrics");
        }

        // M3 (invariant 3): only measured values; was a hardcoded "HEALTHY", 45 ms latency and AI status "UP".
        Long redisStreamLag = null;
        Long dlqCount = null;
        String status = "UNKNOWN";
        if (redisTemplate != null) {
            try {
                redisStreamLag = redisTemplate.opsForStream().size("mend:webhooks");
                dlqCount = redisTemplate.opsForStream().size("mend:webhooks:dlq");
                status = "UP";
            } catch (Exception e) {
                log.trace("Error fetching Redis stream metrics: {}", e.getMessage());
            }
        }

        OperationalHealthDto health = new OperationalHealthDto(status, redisStreamLag, dlqCount);

        return ResponseEntity.ok(health);
    }

    private boolean isMerchantAdmin(AuthenticatedUser currentUser) {
        return currentUser.getMemberships() != null &&
               currentUser.getMemberships().stream().anyMatch(m -> "MERCHANT_ADMIN".equalsIgnoreCase(m.getRoleName()));
    }
}
