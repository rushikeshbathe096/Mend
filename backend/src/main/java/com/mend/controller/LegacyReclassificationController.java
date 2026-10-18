package com.mend.controller;

import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.service.LegacyReclassificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** M4 U4.8(a): SYSTEM_ADMIN reclassification of a merchant's pre-M3 campaigns into new deterministic rows (no action taken). */
@RestController
@RequestMapping("/api/v1/internal/merchants/{merchantId}/reclassify-legacy")
public class LegacyReclassificationController {

    private final LegacyReclassificationService service;

    public LegacyReclassificationController(LegacyReclassificationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<LegacyReclassificationService.Result, Integer>> reclassify(@PathVariable UUID merchantId,
                                                                                         @CurrentUser AuthenticatedUser currentUser) {
        if (currentUser == null || !currentUser.isSystemAdmin()) {
            throw new TenantAccessDeniedException("Legacy reclassification requires SYSTEM_ADMIN");
        }
        return ResponseEntity.ok(service.reclassifyMerchant(merchantId));
    }
}
