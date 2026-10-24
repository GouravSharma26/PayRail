package com.payrail.transfer.repository;

import com.payrail.transfer.domain.ProcessingAttemptRecord;

import java.util.List;
import java.util.UUID;

public interface ProcessingAttemptRepository {

    void insert(ProcessingAttemptRecord record);

    List<ProcessingAttemptRecord> findByTransferId(UUID transferId);
}
