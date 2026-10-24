package com.payrail.ops.dto;

import com.payrail.reconciliation.domain.ReconciliationIssueRecord;
import com.payrail.reconciliation.domain.ReconciliationRunRecord;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Details of a reconciliation audit run including detected discrepancies")
public record ReconciliationRunDetailResponse(
        @Schema(description = "Reconciliation run execution metadata and summary")
        ReconciliationRunRecord run,

        @Schema(description = "List of issues/discrepancies detected during this run")
        List<ReconciliationIssueRecord> issues
) {}
