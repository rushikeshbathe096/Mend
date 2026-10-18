package com.mend.controller;

import com.mend.domain.entity.EvalSnapshot;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.service.EvalSnapshotService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * M4 U4.3: on-demand evaluation snapshots (SYSTEM_ADMIN only; the snapshot spans merchants). Used by
 * {@code scripts/eval/snapshot.sh}. Returns metadata only; the files stay on the server under {@code mend.eval.snapshot.dir}.
 */
@RestController
@RequestMapping("/api/v1/internal/eval/snapshots")
public class EvalSnapshotController {

    private final EvalSnapshotService snapshotService;

    public EvalSnapshotController(EvalSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    public record CreateSnapshotRequest(Instant windowFrom, Instant windowTo, Instant asOf, Integer horizonDays, List<UUID> merchantIds) {
    }

    @PostMapping
    public ResponseEntity<EvalSnapshot> create(@RequestBody CreateSnapshotRequest request, @CurrentUser AuthenticatedUser currentUser) {
        requireSystemAdmin(currentUser);
        if (request == null || request.windowFrom() == null || request.windowTo() == null) {
            throw new InvalidRequestException("windowFrom and windowTo are required");
        }
        EvalSnapshot snapshot = snapshotService.createSnapshot(new EvalSnapshotService.Request(request.windowFrom(), request.windowTo(),
                request.asOf(), request.horizonDays(), request.merchantIds()));
        return ResponseEntity.status(HttpStatus.CREATED).body(snapshot);
    }

    @GetMapping
    public ResponseEntity<List<EvalSnapshot>> recent(@CurrentUser AuthenticatedUser currentUser) {
        requireSystemAdmin(currentUser);
        return ResponseEntity.ok(snapshotService.recent());
    }

    private static void requireSystemAdmin(AuthenticatedUser currentUser) {
        if (currentUser == null || !currentUser.isSystemAdmin()) {
            throw new TenantAccessDeniedException("Evaluation snapshots require SYSTEM_ADMIN");
        }
    }
}
