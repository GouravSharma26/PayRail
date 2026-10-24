package com.payrail;

import com.payrail.account.domain.AccountRecord;
import com.payrail.account.dto.AccountResponse;
import com.payrail.account.dto.CreateAccountRequest;
import com.payrail.account.repository.AccountRepository;
import com.payrail.ledger.service.ReconciliationService;
import com.payrail.reconciliation.domain.ReconciliationIssueRecord;
import com.payrail.reconciliation.domain.ReconciliationRunRecord;
import com.payrail.reconciliation.repository.ReconciliationIssueRepository;
import com.payrail.reconciliation.repository.ReconciliationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationJobIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private ReconciliationRunRepository runRepository;

    @Autowired
    private ReconciliationIssueRepository issueRepository;

    @Autowired
    private AccountRepository accountRepository;

    private UUID accountAId;
    private UUID accountBId;

    @BeforeEach
    void setup() {
        AccountResponse accA = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Recon Job A", "INR", "1000.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accA).isNotNull();
        accountAId = accA.id();

        AccountResponse accB = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Recon Job B", "INR", "500.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accB).isNotNull();
        accountBId = accB.id();
    }

    @Test
    @DisplayName("Clean audit should record status CLEAN with zero issues in reconciliation_runs")
    void shouldRecordCleanAuditRun() {
        ReconciliationRunRecord run = reconciliationService.runAuditAndPersist("MANUAL");

        assertThat(run).isNotNull();
        assertThat(run.status()).isEqualTo("CLEAN");
        assertThat(run.issueCount()).isEqualTo(0);
        assertThat(run.triggerType()).isEqualTo("MANUAL");
        assertThat(run.finishedAt()).isNotNull();

        ReconciliationRunRecord persistedRun = runRepository.findById(run.id()).orElseThrow();
        assertThat(persistedRun.status()).isEqualTo("CLEAN");
        assertThat(persistedRun.scannedAccountsCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Discrepancy audit should persist issue record without altering ledger or account balances")
    void shouldPersistIssuesWithoutSilentAutoRepair() {
        // Tamper with account balance using withTriggersDisabled to simulate corruption
        withTriggersDisabled(() ->
                jdbcTemplate.update("UPDATE accounts SET current_balance = 9999.00 WHERE id = ?", accountAId)
        );

        ReconciliationRunRecord run = reconciliationService.runAuditAndPersist("SCHEDULED");

        assertThat(run.status()).isEqualTo("DISCREPANCIES_DETECTED");
        assertThat(run.issueCount()).isGreaterThanOrEqualTo(1);

        List<ReconciliationIssueRecord> issues = issueRepository.findByRunId(run.id());
        assertThat(issues).isNotEmpty();

        ReconciliationIssueRecord balanceIssue = issues.stream()
                .filter(i -> "ACCOUNTING_DISCREPANCY".equals(i.issueType()))
                .findFirst()
                .orElseThrow();
        assertThat(balanceIssue.severity()).isEqualTo("CRITICAL");
        assertThat(balanceIssue.status()).isEqualTo("OPEN");

        // CRITICAL INVARIANT: Reconciliation detects; it does NOT silently repair balances!
        AccountRecord accountAfterRecon = accountRepository.findById(accountAId).orElseThrow();
        assertThat(accountAfterRecon.currentBalance()).isEqualByComparingTo(new BigDecimal("9999.00"));
    }
}
