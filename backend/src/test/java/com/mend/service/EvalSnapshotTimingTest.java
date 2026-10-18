package com.mend.service;

import com.mend.controller.DemoController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** M4 U4.3 review: the settle margin, the nightly window arithmetic and the demo-source constant. */
class EvalSnapshotTimingTest {

    @Test
    @DisplayName("as_of must be at least the settle margin in the past")
    void settleMargin() {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        Duration margin = Duration.ofMinutes(15);
        EvalSnapshotService.requireSettled(now.minus(margin), now, margin);
        assertThrows(IllegalArgumentException.class, () -> EvalSnapshotService.requireSettled(now.minus(Duration.ofMinutes(14)), now, margin));
        assertThrows(IllegalArgumentException.class, () -> EvalSnapshotService.requireSettled(now.plusSeconds(1), now, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> EvalSnapshotService.requireSettled(null, now, margin));
    }

    @Test
    @DisplayName("Nightly job: window [today − H − 7 d, today − H), as_of today 00:00 UTC, all merchants")
    void nightlyWindow() {
        EvalSnapshotService service = mock(EvalSnapshotService.class);
        AtomicReference<EvalSnapshotService.Request> seen = new AtomicReference<>();
        when(service.createSnapshot(any())).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return mock(com.mend.domain.entity.EvalSnapshot.class);
        });
        new EvalSnapshotJob(service, 14).snapshot(LocalDate.parse("2026-10-19"));
        EvalSnapshotService.Request r = seen.get();
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"), r.asOf());
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), r.windowTo());
        assertEquals(Instant.parse("2026-09-28T00:00:00Z"), r.windowFrom());
        assertEquals(14, r.horizonDays());
        assertEquals(List.of(), r.merchantIds());
    }

    @Test
    @DisplayName("The snapshot's demo-source rule uses the demo's own source value")
    void demoSourceMatches() throws Exception {
        var field = DemoController.class.getDeclaredField("DEMO_SOURCE");
        field.setAccessible(true);
        assertEquals(field.get(null), EvalSnapshotService.DEMO_SOURCE);
    }
}
