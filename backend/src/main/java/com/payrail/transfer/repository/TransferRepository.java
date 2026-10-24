package com.payrail.transfer.repository;

import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.dto.TransferSearchCriteria;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository {

    void insert(TransferRecord transfer);

    Optional<TransferRecord> findById(UUID id);

    List<TransferRecord> search(TransferSearchCriteria criteria);

    long count(TransferSearchCriteria criteria);
}
