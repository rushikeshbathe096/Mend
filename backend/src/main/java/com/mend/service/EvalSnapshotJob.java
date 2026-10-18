package com.mend.service;

import com.mend.evaluation.snapshot.SnapshotParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * M4 U4.3: nightly snapshot (off by default: {@code mend.eval.snapshot.schedule-enabled=false}). Window: the 7 UTC days
 * whose campaigns are mature today, i.e. first eligibility in [today − H − 7 d, today − H); as_of = today 00:00 UTC.
 */
@Component
@ConditionalOnProperty(name = "mend.eval.snapshot.schedule-enabled", havingValue = "true")
public class EvalSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(EvalSnapshotJob.class);

    private final EvalSnapshotService snapshotService;
    private final int horizonDays;

    public EvalSnapshotJob(EvalSnapshotService snapshotService,
                           @Value("${mend.eval.snapshot.horizon-days:" + SnapshotParams.DEFAULT_HORIZON_DAYS + "}") int horizonDays) {
        this.snapshotService = snapshotService;
        this.horizonDays = horizonDays;
    }

    @Scheduled(cron = "${mend.eval.snapshot.cron:0 30 2 * * *}", zone = "UTC")
    public void nightly() {
        snapshot(LocalDate.now(ZoneOffset.UTC));
    }

    void snapshot(LocalDate today) {
        Instant asOf = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = asOf.minus(Duration.ofDays(horizonDays));
        Instant from = to.minus(Duration.ofDays(7));
        var snapshot = snapshotService.createSnapshot(new EvalSnapshotService.Request(from, to, asOf, horizonDays, List.of()));
        log.info("event=eval_snapshot_nightly id={} status={}", snapshot.getId(), snapshot.getStatus());
    }
}
