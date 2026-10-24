package com.payrail.ops.controller;

import com.payrail.ops.dto.OutboxStatusResponse;
import com.payrail.ops.dto.ReconciliationRunDetailResponse;
import com.payrail.ops.dto.SystemHealthSummaryResponse;
import com.payrail.ops.service.OpsService;
import com.payrail.reconciliation.domain.ReconciliationRunRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ops")
@Tag(name = "Operations", description = "Operational visibility, reconciliation audits, outbox telemetry, and system diagnostics")
public class OpsController {

    private final OpsService opsService;

    public OpsController(OpsService opsService) {
        this.opsService = opsService;
    }

    @GetMapping("/reconciliation/runs")
    @Operation(
            summary = "List recent reconciliation runs",
            description = "Returns the most recent reconciliation audit runs ordered by finished timestamp descending."
    )
    @ApiResponse(responseCode = "200", description = "List of reconciliation runs")
    public ResponseEntity<List<ReconciliationRunRecord>> listReconciliationRuns(
            @Parameter(description = "Maximum number of runs to return (default 20, max 100)")
            @RequestParam(defaultValue = "20") int limit
    ) {
        List<ReconciliationRunRecord> runs = opsService.getRecentReconciliationRuns(limit);
        return ResponseEntity.ok(runs);
    }

    @GetMapping("/reconciliation/runs/{runId}")
    @Operation(
            summary = "Get reconciliation run detail",
            description = "Returns execution details and all detected discrepancies/issues for a specific reconciliation run."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reconciliation run details with issue list"),
            @ApiResponse(responseCode = "404", description = "Reconciliation run not found")
    })
    public ResponseEntity<ReconciliationRunDetailResponse> getReconciliationRun(
            @Parameter(description = "UUID of the reconciliation run", required = true)
            @PathVariable UUID runId
    ) {
        return opsService.getReconciliationRunDetail(runId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/reconciliation/trigger")
    @Operation(
            summary = "Trigger manual reconciliation audit",
            description = "Triggers an immediate database-wide audit across accounts, transfers, ledger entries, and outbox state. Durably records findings and returns the run result."
    )
    @ApiResponse(responseCode = "200", description = "Reconciliation run executed and recorded")
    public ResponseEntity<ReconciliationRunRecord> triggerReconciliation() {
        ReconciliationRunRecord result = opsService.triggerReconciliation();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/outbox/status")
    @Operation(
            summary = "Get outbox event backlog status",
            description = "Returns counts of pending, failed, and retry-exhausted outbox events, plus age of the oldest unpublished event."
    )
    @ApiResponse(responseCode = "200", description = "Outbox backlog telemetry")
    public ResponseEntity<OutboxStatusResponse> getOutboxStatus() {
        OutboxStatusResponse status = opsService.getOutboxStatus();
        return ResponseEntity.ok(status);
    }

    @GetMapping("/health/summary")
    @Operation(
            summary = "Get combined system operational health summary",
            description = "Returns high-level status of DB, Kafka, outbox backlog, and latest reconciliation state."
    )
    @ApiResponse(responseCode = "200", description = "System health summary")
    public ResponseEntity<SystemHealthSummaryResponse> getHealthSummary() {
        SystemHealthSummaryResponse summary = opsService.getHealthSummary();
        return ResponseEntity.ok(summary);
    }
}
