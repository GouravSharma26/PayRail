package com.payrail;

import com.payrail.account.domain.AccountRecord;
import com.payrail.account.domain.AccountStatus;
import com.payrail.account.repository.AccountRepository;
import com.payrail.ledger.service.ReconciliationReport;
import com.payrail.ledger.service.ReconciliationService;
import com.payrail.ledger.service.TransferPostingResult;
import com.payrail.ledger.service.TransferPostingService;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.repository.TransferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerConcurrencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferPostingService postingService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private ReconciliationService reconciliationService;

    @Test
    @DisplayName("Should prevent concurrent overspending: 10 parallel threads competing for 100.00 INR balance (20.00 each) results in exactly 5 successes and 5 insufficient funds")
    void shouldPreventConcurrentOverspending() throws Exception {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Contended Source", "INR", "100.00");
        createAccount(dstId, "Recipient", "INR", "0.00");

        int threadCount = 10;
        BigDecimal transferAmount = new BigDecimal("20.00");

        List<UUID> transferIds = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            UUID tId = UUID.randomUUID();
            transferIds.add(tId);
            createPendingTransfer(tId, srcId, dstId, transferAmount, "INR");
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Callable<TransferPostingResult>> tasks = new ArrayList<>();

        for (UUID tId : transferIds) {
            tasks.add(() -> {
                barrier.await(10, TimeUnit.SECONDS); // Align contender threads at the starting line
                return postingService.postTransfer(tId);
            });
        }

        List<Future<TransferPostingResult>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        boolean finished = executor.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        int successCount = 0;
        int failureCount = 0;

        for (Future<TransferPostingResult> future : futures) {
            TransferPostingResult result = future.get();
            if (result.status() == TransferStatus.SUCCEEDED) {
                successCount++;
            } else if (result.status() == TransferStatus.FAILED) {
                failureCount++;
                assertThat(result.failureCode()).isEqualTo("INSUFFICIENT_FUNDS");
            }
        }

        // Exactly 5 transfers succeed: 5 * 20.00 = 100.00
        assertThat(successCount).isEqualTo(5);
        assertThat(failureCount).isEqualTo(5);

        // Account balances must reflect exact financial movement
        AccountRecord finalSrc = accountRepository.findById(srcId).orElseThrow();
        AccountRecord finalDst = accountRepository.findById(dstId).orElseThrow();
        assertThat(finalSrc.currentBalance()).isEqualByComparingTo("0.00");
        assertThat(finalDst.currentBalance()).isEqualByComparingTo("100.00");

        // Database-wide reconciliation must be completely balanced
        ReconciliationReport report = reconciliationService.runFullAudit();
        assertThat(report.isBalanced()).isTrue();
        assertThat(report.inconsistentAccountBalancesCount()).isZero();
        assertThat(report.unbalancedTransfersCount()).isZero();
    }

    @Test
    @DisplayName("Should eliminate deadlocks under high-concurrency bidirectional transfers between two accounts using deterministic row locking")
    void shouldEliminateDeadlocksUnderBidirectionalContention() throws Exception {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        createAccount(accountA, "Account Alpha", "INR", "500.00");
        createAccount(accountB, "Account Beta", "INR", "500.00");

        int transfersPerDirection = 10;
        int totalThreads = transfersPerDirection * 2;
        BigDecimal amount = new BigDecimal("10.00");

        List<UUID> transfersAtoB = new ArrayList<>();
        List<UUID> transfersBtoA = new ArrayList<>();

        for (int i = 0; i < transfersPerDirection; i++) {
            UUID tId1 = UUID.randomUUID();
            transfersAtoB.add(tId1);
            createPendingTransfer(tId1, accountA, accountB, amount, "INR");

            UUID tId2 = UUID.randomUUID();
            transfersBtoA.add(tId2);
            createPendingTransfer(tId2, accountB, accountA, amount, "INR");
        }

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CyclicBarrier barrier = new CyclicBarrier(totalThreads);
        List<Callable<TransferPostingResult>> tasks = new ArrayList<>();

        for (UUID tId : transfersAtoB) {
            tasks.add(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return postingService.postTransfer(tId);
            });
        }

        for (UUID tId : transfersBtoA) {
            tasks.add(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return postingService.postTransfer(tId);
            });
        }

        Collections.shuffle(tasks);

        List<Future<TransferPostingResult>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        boolean finished = executor.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        int successCount = 0;
        for (Future<TransferPostingResult> future : futures) {
            TransferPostingResult result = future.get();
            assertThat(result.status()).isEqualTo(TransferStatus.SUCCEEDED);
            successCount++;
        }

        assertThat(successCount).isEqualTo(totalThreads);

        // Alpha net movement: - (10 * 10) + (10 * 10) = 0
        // Beta net movement: + (10 * 10) - (10 * 10) = 0
        AccountRecord finalA = accountRepository.findById(accountA).orElseThrow();
        AccountRecord finalB = accountRepository.findById(accountB).orElseThrow();
        assertThat(finalA.currentBalance()).isEqualByComparingTo("500.00");
        assertThat(finalB.currentBalance()).isEqualByComparingTo("500.00");

        ReconciliationReport report = reconciliationService.runFullAudit();
        assertThat(report.isBalanced()).isTrue();
        assertThat(report.inconsistentAccountBalancesCount()).isZero();
        assertThat(report.unbalancedTransfersCount()).isZero();
    }

    private void createAccount(UUID id, String name, String currency, String balance) {
        accountRepository.insert(new AccountRecord(
                id,
                name,
                currency,
                new BigDecimal(balance),
                new BigDecimal(balance),
                AccountStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        ));
    }

    private void createPendingTransfer(UUID id, UUID src, UUID dst, BigDecimal amount, String currency) {
        transferRepository.insert(new TransferRecord(
                id,
                src,
                dst,
                amount,
                currency,
                TransferStatus.PENDING,
                null,
                null,
                UUID.randomUUID(),
                0,
                null,
                null,
                Instant.now(),
                Instant.now(),
                null
        ));
    }
}
