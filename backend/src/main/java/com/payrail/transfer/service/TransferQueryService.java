package com.payrail.transfer.service;

import com.payrail.common.exception.ResourceNotFoundException;
import com.payrail.common.model.PagedResponse;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.dto.ProcessingAttemptResponse;
import com.payrail.transfer.dto.TransferResponse;
import com.payrail.transfer.dto.TransferSearchCriteria;
import com.payrail.transfer.dto.TransferStatusHistoryResponse;
import com.payrail.transfer.repository.ProcessingAttemptRepository;
import com.payrail.transfer.repository.TransferRepository;
import com.payrail.transfer.repository.TransferStatusHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TransferQueryService {

    private final TransferRepository transferRepository;
    private final TransferStatusHistoryRepository statusHistoryRepository;
    private final ProcessingAttemptRepository processingAttemptRepository;

    public TransferQueryService(
            TransferRepository transferRepository,
            TransferStatusHistoryRepository statusHistoryRepository,
            ProcessingAttemptRepository processingAttemptRepository
    ) {
        this.transferRepository = transferRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.processingAttemptRepository = processingAttemptRepository;
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(UUID id) {
        TransferRecord record = transferRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", id));
        return TransferResponse.fromRecord(record);
    }

    @Transactional(readOnly = true)
    public PagedResponse<TransferResponse> searchTransfers(TransferSearchCriteria criteria) {
        List<TransferResponse> items = transferRepository.search(criteria)
                .stream()
                .map(TransferResponse::fromRecord)
                .toList();

        long total = transferRepository.count(criteria);
        return PagedResponse.of(items, criteria.page(), criteria.size(), total);
    }

    @Transactional(readOnly = true)
    public List<TransferStatusHistoryResponse> getTransferHistory(UUID transferId) {
        // Confirm transfer exists first
        if (transferRepository.findById(transferId).isEmpty()) {
            throw new ResourceNotFoundException("Transfer", transferId);
        }
        return statusHistoryRepository.findByTransferId(transferId)
                .stream()
                .map(TransferStatusHistoryResponse::fromRecord)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProcessingAttemptResponse> getProcessingAttempts(UUID transferId) {
        // Confirm transfer exists first
        if (transferRepository.findById(transferId).isEmpty()) {
            throw new ResourceNotFoundException("Transfer", transferId);
        }
        return processingAttemptRepository.findByTransferId(transferId)
                .stream()
                .map(ProcessingAttemptResponse::fromRecord)
                .toList();
    }
}
