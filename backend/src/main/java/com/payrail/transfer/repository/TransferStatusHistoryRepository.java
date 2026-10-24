package com.payrail.transfer.repository;

import com.payrail.transfer.domain.TransferStatusHistoryRecord;

import java.util.List;
import java.util.UUID;

public interface TransferStatusHistoryRepository {

    void insert(TransferStatusHistoryRecord record);

    List<TransferStatusHistoryRecord> findByTransferId(UUID transferId);
}
