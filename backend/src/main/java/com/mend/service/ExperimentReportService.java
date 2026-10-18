package com.mend.service;

import com.mend.domain.entity.EvalSnapshot;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.entity.MerchantExperimentSalt;
import com.mend.domain.repository.EvalSnapshotRepository;
import com.mend.domain.repository.HoldoutAssignmentRepository;
import com.mend.domain.repository.MerchantConfigRepository;
import com.mend.domain.repository.MerchantExperimentSaltRepository;
import com.mend.dto.ExperimentSummaryDto;
import com.mend.evaluation.metrics.Figure;
import com.mend.evaluation.metrics.MetricsReport;
import com.mend.evaluation.metrics.SnapshotFiles;
import com.mend.evaluation.snapshot.Pseudonyms;
import com.mend.evaluation.snapshot.SnapshotRow;
import com.mend.experiment.Arm;
import com.mend.security.AuthenticatedUser;
import com.mend.security.TenantGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * M4 U4.8(c): the experiment figures a merchant sees. Causal figures are computed by {@link MetricsReport} from the latest
 * VALID snapshot that covers the merchant, restricted to the merchant's rows (its pseudonym), never from live tables
 * (invariant 23). Without such a snapshot every causal figure is TBD.
 */
@Service
public class ExperimentReportService {

    private static final Logger log = LoggerFactory.getLogger(ExperimentReportService.class);
    private static final Set<String> SHOWN = Set.of("analysis status", "recovery rate TREATMENT", "recovery rate CONTROL",
            "incremental recovery rate (TREATMENT − CONTROL)", "incremental amount per eligible invoice (INR, TREATMENT − CONTROL)",
            "sample-ratio mismatch (chi-square p; flagged below 0.001)", "contact rate CONTROL (must be 0)");
    static final long SEED = 20261018L;

    private final MerchantConfigRepository merchantConfigRepository;
    private final HoldoutAssignmentRepository assignmentRepository;
    private final EvalSnapshotRepository snapshotRepository;
    private final MerchantExperimentSaltRepository saltRepository;
    private final EvalSnapshotService snapshotService;

    public ExperimentReportService(MerchantConfigRepository merchantConfigRepository, HoldoutAssignmentRepository assignmentRepository,
                                   EvalSnapshotRepository snapshotRepository, MerchantExperimentSaltRepository saltRepository,
                                   EvalSnapshotService snapshotService) {
        this.merchantConfigRepository = merchantConfigRepository;
        this.assignmentRepository = assignmentRepository;
        this.snapshotRepository = snapshotRepository;
        this.saltRepository = saltRepository;
        this.snapshotService = snapshotService;
    }

    @Transactional(readOnly = true)
    public ExperimentSummaryDto forMerchant(UUID merchantId, AuthenticatedUser currentUser) {
        TenantGuard.requireUserAccess(merchantId, currentUser);
        int rate = merchantConfigRepository.findByMerchantId(merchantId).map(MerchantConfig::getHoldoutRateBp).orElse(0);
        var assignments = assignmentRepository.findByMerchantIdOrderByAssignedAtAsc(merchantId);
        long control = assignments.stream().filter(a -> a.getArm() == Arm.CONTROL).count();
        long treatment = assignments.size() - control;
        String note = "Arm counts are operational (live, not causal). Observed recovery is not incremental recovery: only the "
                + "TREATMENT − CONTROL figures from a VALID snapshot are causal.";

        Optional<byte[]> salt = saltRepository.findFirstByMerchantIdAndPurposeOrderBySaltVersionDesc(merchantId, MerchantExperimentSalt.EVAL_ID)
                .map(MerchantExperimentSalt::getSalt);
        for (EvalSnapshot s : snapshotRepository.findTop50ByOrderByCreatedAtDesc()) {
            if (s.getStatus() != EvalSnapshot.Status.VALID || salt.isEmpty() || !covers(s, merchantId)) {
                continue;
            }
            Path dir = snapshotService.snapshotDir().resolve(s.getId().toString());
            if (!Files.isRegularFile(dir.resolve("manifest.json"))) {
                continue;
            }
            try {
                SnapshotFiles all = SnapshotFiles.read(dir, false);
                String ref = new Pseudonyms(Map.of(merchantId, salt.get())).merchantRef(merchantId);
                List<SnapshotRow> mine = all.rows().stream().filter(r -> ref.equals(r.merchantRef())).toList();
                if (mine.isEmpty()) {
                    continue;
                }
                SnapshotFiles merchantOnly = new SnapshotFiles(all.snapshotId(), all.sha256(), all.asOf(), all.horizonDays(),
                        all.providerMode(), all.status(), mine, all.defects());
                MetricsReport.Report report = MetricsReport.compute(merchantOnly, new MetricsReport.Options(SEED, 2000, null, null));
                List<ExperimentSummaryDto.FigureDto> figures = report.figures().stream()
                        .filter(f -> f.scope().equals("overall") && f.stratum().startsWith("holdout ") && !f.stratum().contains(" / ")
                                && SHOWN.contains(f.name()))
                        .map(ExperimentReportService::dto).toList();
                return new ExperimentSummaryDto(rate, treatment, control,
                        new ExperimentSummaryDto.Snapshot(s.getId(), s.getAsOf(), s.getHorizonDays(), s.getProviderMode(),
                                report.label().name(), report.interim()), figures, note);
            } catch (RuntimeException unreadable) {
                log.warn("event=experiment_snapshot_unreadable id={} error={}", s.getId(), unreadable.getClass().getSimpleName());
            }
        }
        return new ExperimentSummaryDto(rate, treatment, control, null, List.of(new ExperimentSummaryDto.FigureDto("-",
                "incremental recovery rate (TREATMENT − CONTROL)", "TBD", null, null, 0, "TBD", "TBD",
                "no VALID evaluation snapshot covers this merchant yet")), note);
    }

    private static boolean covers(EvalSnapshot s, UUID merchantId) {
        Object ids = s.getFilters() == null ? null : s.getFilters().get("merchant_ids");
        return !(ids instanceof List<?> list) || list.isEmpty() || list.contains(merchantId.toString());
    }

    private static ExperimentSummaryDto.FigureDto dto(Figure f) {
        return new ExperimentSummaryDto.FigureDto(f.stratum(), f.name(), f.value(), f.low(), f.high(), f.n(), f.label().name(),
                f.status().name(), f.note());
    }
}
