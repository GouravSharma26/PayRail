package com.payrail.transfer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.account.domain.AccountRecord;
import com.payrail.account.domain.AccountStatus;
import com.payrail.account.repository.AccountRepository;
import com.payrail.common.exception.AccountInactiveException;
import com.payrail.common.exception.CurrencyMismatchException;
import com.payrail.common.exception.IdempotencyKeyReusedException;
import com.payrail.common.exception.InvalidTransferException;
import com.payrail.common.exception.ResourceNotFoundException;
import com.payrail.common.filter.CorrelationIdFilter;
import com.payrail.common.util.AmountParser;
import com.payrail.common.util.CanonicalRequestHasher;
import com.payrail.idempotency.domain.IdempotencyRecord;
import com.payrail.idempotency.repository.IdempotencyRepository;
import com.payrail.outbox.domain.OutboxEventRecord;
import com.payrail.outbox.repository.OutboxRepository;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatusHistoryRecord;
import com.payrail.transfer.dto.CreateTransferRequest;
import com.payrail.transfer.dto.TransferResponse;
import com.payrail.transfer.dto.TransferSubmissionResult;
import com.payrail.transfer.repository.TransferRepository;
import com.payrail.transfer.repository.TransferStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(TransferSubmissionService.class);

    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final TransferStatusHistoryRepository statusHistoryRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public TransferSubmissionService(
            TransferRepository transferRepository,
            AccountRepository accountRepository,
            IdempotencyRepository idempotencyRepository,
            TransferStatusHistoryRepository statusHistoryRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper
    ) {
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TransferSubmissionResult submitTransfer(String idempotencyKey, CreateTransferRequest request) {
        // 1. Validate Idempotency-Key
        CanonicalRequestHasher.validateIdempotencyKey(idempotencyKey);

        // 2. Parse and validate amount
        BigDecimal amount = AmountParser.parseAmount("amount", request.amount(), false);
        String currency = request.currency().trim().toUpperCase();

        // 3. Validate accounts are distinct
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new InvalidTransferException("Source and destination accounts must be different.");
        }

        // 4. Validate accounts exist and are ACTIVE
        AccountRecord sourceAccount = accountRepository.findById(request.sourceAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Source account", request.sourceAccountId()));

        if (sourceAccount.status() != AccountStatus.ACTIVE) {
            throw new AccountInactiveException(String.format("Source account '%s' is inactive.", request.sourceAccountId()));
        }

        AccountRecord destinationAccount = accountRepository.findById(request.destinationAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Destination account", request.destinationAccountId()));

        if (destinationAccount.status() != AccountStatus.ACTIVE) {
            throw new AccountInactiveException(String.format("Destination account '%s' is inactive.", request.destinationAccountId()));
        }

        // 5. Validate currencies match
        if (!sourceAccount.currency().equalsIgnoreCase(currency) || !destinationAccount.currency().equalsIgnoreCase(currency)) {
            throw new CurrencyMismatchException(String.format(
                    "Currency mismatch: transfer currency '%s' must match source account '%s' and destination account '%s'.",
                    currency, sourceAccount.currency(), destinationAccount.currency()
            ));
        }

        // 6. Compute canonical request hash
        String requestHash = CanonicalRequestHasher.computeHash(
                request.sourceAccountId(),
                request.destinationAccountId(),
                amount,
                currency
        );

        String safeKey = CanonicalRequestHasher.safeFingerprint(idempotencyKey);

        // 7. Check if idempotency record exists
        Optional<IdempotencyRecord> existingRecord = idempotencyRepository.findByKey(idempotencyKey);
        if (existingRecord.isPresent()) {
            return handleExistingIdempotencyRecord(existingRecord.get(), requestHash, safeKey);
        }

        // 8. Atomically insert Transfer, Idempotency, Status History, and Outbox event
        UUID transferId = UUID.randomUUID();
        UUID correlationId = UUID.fromString(CorrelationIdFilter.getCurrentCorrelationId());
        TransferRecord pendingTransfer = TransferRecord.createPending(
                transferId,
                request.sourceAccountId(),
                request.destinationAccountId(),
                amount,
                currency,
                correlationId
        );

        try {
            // A. Insert Transfer
            transferRepository.insert(pendingTransfer);

            // B. Insert Idempotency Record
            IdempotencyRecord newIdempotencyRecord = new IdempotencyRecord(
                    idempotencyKey,
                    requestHash,
                    transferId,
                    Instant.now()
            );
            idempotencyRepository.insert(newIdempotencyRecord);

            // C. Insert Initial Status History
            TransferStatusHistoryRecord history = TransferStatusHistoryRecord.initialSubmission(transferId, correlationId);
            statusHistoryRepository.insert(history);

            // D. Insert Outbox Event
            String payloadJson;
            try {
                payloadJson = objectMapper.writeValueAsString(Map.of("transferId", transferId.toString()));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize outbox event payload", e);
            }

            OutboxEventRecord outboxEvent = OutboxEventRecord.createPending(
                    UUID.randomUUID(),
                    "transfer",
                    transferId,
                    1L,
                    "transfer.requested",
                    1,
                    payloadJson,
                    correlationId
            );
            outboxRepository.insert(outboxEvent);

            log.info("Submitted new transfer id={} [{}] amount={} {}", transferId, safeKey, amount, currency);
            return new TransferSubmissionResult(TransferResponse.fromRecord(pendingTransfer), false);

        } catch (DuplicateKeyException e) {
            log.warn("Concurrent idempotency insertion detected for [{}], re-evaluating record", safeKey);
            IdempotencyRecord racedRecord = idempotencyRepository.findByKey(idempotencyKey)
                    .orElseThrow(() -> e);
            return handleExistingIdempotencyRecord(racedRecord, requestHash, safeKey);
        }
    }

    private TransferSubmissionResult handleExistingIdempotencyRecord(IdempotencyRecord record, String currentHash, String safeKey) {
        if (!record.requestHash().equals(currentHash)) {
            log.warn("Idempotency key reuse conflict detected for [{}]", safeKey);
            throw new IdempotencyKeyReusedException(
                    "Idempotency-Key has already been used with a different request payload."
            );
        }

        log.info("Idempotent replay for transfer id={} [{}]", record.transferId(), safeKey);
        TransferRecord existingTransfer = transferRepository.findById(record.transferId())
                .orElseThrow(() -> new IllegalStateException("Corrupt idempotency record pointing to missing transfer: " + record.transferId()));

        return new TransferSubmissionResult(TransferResponse.fromRecord(existingTransfer), true);
    }
}
